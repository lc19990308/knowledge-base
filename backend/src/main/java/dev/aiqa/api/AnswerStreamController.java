package dev.aiqa.api;

import dev.aiqa.answer.AnswerStreams;
import dev.aiqa.auth.AppUser;
import dev.aiqa.auth.CurrentUser;
import dev.aiqa.common.ApiException;
import dev.aiqa.common.RateLimiter;
import dev.aiqa.conversation.QuestionRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 单条回答的 SSE 增量流。
 *
 * <p>只负责把 worker 已经拿到的模型增量转给浏览器；收到 {@code done} 后客户端仍要读一次
 * {@code GET /api/v1/questions/{id}}，段落类型、引用和证据有效性都以那次结果为准。
 * 流不可用（进程重启、通道已清理、代理不支持）时客户端回退到原来的轮询，不改变最终回答。
 */
@RestController
@RequestMapping("/api/v1")
public class AnswerStreamController {

  /** 没有增量也要定期写一行注释，避免代理把长时间安静的长连接掐掉。 */
  private static final long HEARTBEAT_SECONDS = 15L;

  /** 增量事件的载荷：一段新增文本。 */
  public record DeltaEvent(String text) {}

  /** 结束事件的载荷：问题最终状态；业务失败码可能为空。 */
  public record DoneEvent(String status, String failureCode) {}

  /** 生成出错事件的载荷。 */
  public record FailureEvent(String code) {}

  /** 超时事件的载荷：客户端应回退轮询，而不是当作回答失败。 */
  public record TimeoutEvent(boolean timeout) {}

  private final CurrentUser current;
  private final QuestionRepository questions;
  private final AnswerStreams streams;
  private final RateLimiter limiter;
  private final long streamTimeoutSeconds;

  /**
   * 定时器只做心跳与超时收尾，单独一条守护线程，不占用 worker 的调度线程池。
   * 每个连接只挂两个任务，代价很小。
   */
  private final ScheduledExecutorService scheduler =
      Executors.newSingleThreadScheduledExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "answer-stream");
            thread.setDaemon(true);
            return thread;
          });

  public AnswerStreamController(
      CurrentUser current,
      QuestionRepository questions,
      AnswerStreams streams,
      RateLimiter limiter,
      @Value("${aiqa.answer.stream-timeout-seconds:120}") long streamTimeoutSeconds) {
    this.current = current;
    this.questions = questions;
    this.streams = streams;
    this.limiter = limiter;
    this.streamTimeoutSeconds = streamTimeoutSeconds;
  }

  /**
   * 订阅一条问题的回答增量。
   *
   * <p>问题已结束时直接回一个 {@code done} 事件，客户端照常读取详情接口；
   * 只有进行中的问题才真正挂在通道上等增量。
   */
  @GetMapping(value = "/questions/{questionId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable String questionId) {
    AppUser user = current.require();
    UUID id = Http.pathUuid(questionId, "questionId");
    QuestionRepository.QuestionRow question =
        questions.find(id, user.id()).orElseThrow(ApiException::notFound);
    // 长连接算一次状态读取，与轮询共用同一个限流口径，避免绕过读取配额。
    limiter.read(RateLimiter.Read.STATUS, user.id(), () -> null);

    SseEmitter emitter = new SseEmitter(0L);
    StreamSubscription subscription = new StreamSubscription(id, emitter);
    // 已结束且缓冲也过期：没有增量可回放，直接收尾；否则订阅，能补发缓冲文本再结束。
    if (isFinished(question.status()) && !streams.has(id)) {
      subscription.onDone(question.status(), question.failureCode());
      return emitter;
    }
    // 先订阅再启动定时器：回放可能同步触发 done，此时不应再挂心跳。
    streams.subscribe(id, subscription);
    subscription.startTimers(streamTimeoutSeconds, HEARTBEAT_SECONDS);
    return emitter;
  }

  /** 只有成功与失败是终态；排队中与生成中都还要等增量。 */
  private static boolean isFinished(String status) {
    return "SUCCEEDED".equals(status) || "FAILED".equals(status);
  }

  @PreDestroy
  void shutdown() {
    scheduler.shutdownNow();
  }

  /**
   * 一条 SSE 连接的写入器：负责把增量、结束、错误写成事件，并在断开时退订。
   *
   * <p>所有发送都在对象锁内完成，SseEmitter 本身不保证并发写安全；
   * 客户端断开后继续写会抛异常，这里吞掉并当作已关闭处理。
   */
  private final class StreamSubscription implements AnswerStreams.Subscriber {

    private final UUID questionId;
    private final SseEmitter emitter;
    private boolean closed;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> deadline;

    StreamSubscription(UUID questionId, SseEmitter emitter) {
      this.questionId = questionId;
      this.emitter = emitter;
      emitter.onCompletion(this::close);
      emitter.onTimeout(this::close);
      emitter.onError(error -> close());
    }

    @Override
    public void onDelta(String text) {
      send("delta", new DeltaEvent(text));
    }

    @Override
    public void onDone(String status, String failureCode) {
      send("done", new DoneEvent(status, failureCode));
      complete();
    }

    @Override
    public void onFailure(String code) {
      send("error", new FailureEvent(code));
      complete();
    }

    /** 挂上心跳与总超时；连接已关闭时什么都不做。 */
    void startTimers(long timeoutSeconds, long heartbeatSeconds) {
      synchronized (this) {
        if (closed) {
          return;
        }
        heartbeat =
            scheduler.scheduleAtFixedRate(
                this::heartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        deadline = scheduler.schedule(this::expire, timeoutSeconds, TimeUnit.SECONDS);
      }
    }

    private void heartbeat() {
      synchronized (this) {
        if (closed) {
          return;
        }
        try {
          emitter.send(SseEmitter.event().comment("keep-alive"));
        } catch (IOException | IllegalStateException ex) {
          close();
        }
      }
    }

    /** 等太久仍没有结果：告诉客户端回退轮询，然后正常关闭连接。 */
    private void expire() {
      send("timeout", new TimeoutEvent(true));
      complete();
    }

    private void send(String event, Object payload) {
      synchronized (this) {
        if (closed) {
          return;
        }
        try {
          emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException ex) {
          close();
        }
      }
    }

    private void complete() {
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
        cancelTimers();
        streams.unsubscribe(questionId, this);
      }
      try {
        emitter.complete();
      } catch (IllegalStateException ignored) {
        // 连接已经被容器收走，无需再处理。
      }
    }

    private void close() {
      synchronized (this) {
        if (closed) {
          return;
        }
        closed = true;
        cancelTimers();
        streams.unsubscribe(questionId, this);
      }
    }

    private void cancelTimers() {
      if (heartbeat != null) {
        heartbeat.cancel(false);
      }
      if (deadline != null) {
        deadline.cancel(false);
      }
    }
  }
}
