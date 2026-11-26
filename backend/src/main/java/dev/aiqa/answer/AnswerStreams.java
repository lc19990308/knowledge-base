package dev.aiqa.answer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 进程内的回答增量广播：worker 收到模型增量就写进来，SSE 连接按问题订阅。
 *
 * <p>这里只负责「把正在生成的字尽快送到浏览器」，回答的权威副本仍是数据库里的段落与引用；
 * 进程重启或通道被清理后，客户端按问题状态回退轮询，最终结果不受影响。
 *
 * <p>单实例内存广播，与现有「限流在进程内、任务用数据库租约」的边界一致；多实例部署需要换成
 * Redis 之类的共享通道，届时只需替换本类的实现。
 */
@Component
public class AnswerStreams {

  /** 单条回答最多缓存的字符数：超出后不再为后来的订阅补发，只继续推给已经连上的订阅者。 */
  static final int MAX_BUFFERED_CHARS = 64_000;

  /** 结束后的通道至少保留这么久，覆盖「回答刚结束、客户端才连上」的订阅。 */
  static final long RETAIN_AFTER_FINISH_MS = 60_000L;

  /** 既没有结束也没有订阅者的通道，超过这个时间直接丢弃，避免后台任务异常时泄漏。 */
  static final long MAX_CHANNEL_AGE_MS = 30 * 60_000L;

  /** 订阅者由传输层实现；回调发生在 worker 线程上，实现必须快速返回。 */
  public interface Subscriber {

    /** 收到一段新的回答文本。 */
    void onDelta(String text);

    /** 回答流程已经结束，客户端随后以问题详情接口为准。 */
    void onDone(String status, String failureCode);

    /** 生成过程中出错，客户端应回退轮询。 */
    void onFailure(String code);
  }

  private static final class Channel {
    private final StringBuilder text = new StringBuilder();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final long createdAt = System.currentTimeMillis();
    private boolean finished;
    private String status;
    private String failureCode;
    private String failure;
    private long finishedAt;
  }

  private final Map<UUID, Channel> channels = new ConcurrentHashMap<>();

  /**
   * 追加一段模型增量并同步推给订阅者。
   *
   * <p>同一回答的增量和结束事件都在通道锁内发送，客户端不会先收到「结束」再收到迟到的增量。
   */
  public void delta(UUID questionId, String text) {
    if (questionId == null || text == null || text.isEmpty()) {
      return;
    }
    Channel channel = channels.computeIfAbsent(questionId, key -> new Channel());
    synchronized (channel) {
      if (channel.finished) {
        return;
      }
      int room = MAX_BUFFERED_CHARS - channel.text.length();
      if (room > 0) {
        channel.text.append(text.length() <= room ? text : text.substring(0, room));
      }
      for (Subscriber subscriber : channel.subscribers) {
        subscriber.onDelta(text);
      }
    }
  }

  /** 回答正常结束（成功或业务失败）：保留状态一段时间，让随后连上的订阅也能立刻收尾。 */
  public void finish(UUID questionId, String status, String failureCode) {
    if (questionId == null) {
      return;
    }
    Channel channel = channels.computeIfAbsent(questionId, key -> new Channel());
    synchronized (channel) {
      if (channel.finished) {
        return;
      }
      channel.finished = true;
      channel.status = status;
      channel.failureCode = failureCode;
      channel.finishedAt = System.currentTimeMillis();
      for (Subscriber subscriber : List.copyOf(channel.subscribers)) {
        subscriber.onDone(status, failureCode);
      }
      channel.subscribers.clear();
    }
  }

  /** 生成过程出错：客户端据此回退轮询，之后仍以数据库状态为准。 */
  public void fail(UUID questionId, String code) {
    if (questionId == null) {
      return;
    }
    Channel channel = channels.computeIfAbsent(questionId, key -> new Channel());
    synchronized (channel) {
      if (channel.finished) {
        return;
      }
      channel.finished = true;
      channel.failure = code;
      channel.finishedAt = System.currentTimeMillis();
      for (Subscriber subscriber : List.copyOf(channel.subscribers)) {
        subscriber.onFailure(code);
      }
      channel.subscribers.clear();
    }
  }

  /**
   * 订阅一条回答：先回放已有增量，再注册后续回调。
   *
   * <p>通道已结束时同样先回放缓冲文本再发结果，这样「回答瞬间完成、客户端随后才连上」
   * 也能看到一次增量，前端照常逐字显示；缓冲过期后由调用方按问题状态直接收尾。
   *
   * <p>回放与注册在同一把锁内完成，保证「先有增量、后有结束」的发送顺序。
   */
  public void subscribe(UUID questionId, Subscriber subscriber) {
    if (questionId == null || subscriber == null) {
      return;
    }
    Channel channel = channels.computeIfAbsent(questionId, key -> new Channel());
    synchronized (channel) {
      if (channel.text.length() > 0) {
        subscriber.onDelta(channel.text.toString());
      }
      if (channel.finished) {
        if (channel.failure != null) {
          subscriber.onFailure(channel.failure);
        } else {
          subscriber.onDone(channel.status, channel.failureCode);
        }
        return;
      }
      channel.subscribers.add(subscriber);
    }
  }

  /** 通道是否还在内存里（含结束后保留的一段时间），调用方据此决定是否直接按状态收尾。 */
  public boolean has(UUID questionId) {
    return questionId != null && channels.containsKey(questionId);
  }

  /** 连接断开时移除订阅，避免后台继续往已关闭的响应写数据。 */
  public void unsubscribe(UUID questionId, Subscriber subscriber) {
    Channel channel = channels.get(questionId);
    if (channel == null) {
      return;
    }
    synchronized (channel) {
      channel.subscribers.remove(subscriber);
    }
  }

  /**
   * 清理过期通道：已结束的按保留时长删除，长期无人订阅的中间态通道一并收回。
   *
   * <p>由维护任务定时调用，不参与请求路径。
   */
  public void purgeExpired() {
    purgeExpired(System.currentTimeMillis());
  }

  /** 指定当前时间的清理版本，便于测试覆盖"结束后保留多久"的边界。 */
  void purgeExpired(long now) {
    channels.entrySet().removeIf(entry -> {
      Channel channel = entry.getValue();
      synchronized (channel) {
        if (channel.finished) {
          return now - channel.finishedAt > RETAIN_AFTER_FINISH_MS;
        }
        return channel.subscribers.isEmpty() && now - channel.createdAt > MAX_CHANNEL_AGE_MS;
      }
    });
  }

  /** 当前保留的通道数，供健康检查与测试断言使用。 */
  public int size() {
    return channels.size();
  }
}
