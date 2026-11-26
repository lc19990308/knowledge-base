package dev.aiqa.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 增量广播的单元测试：回放顺序、结束通知和通道清理，不依赖数据库与 Spring。 */
class AnswerStreamsTest {

  private final AnswerStreams streams = new AnswerStreams();

  /** 记录所有回调，断言顺序用。 */
  private static final class Recorder implements AnswerStreams.Subscriber {

    private final List<String> events = new ArrayList<>();

    @Override
    public void onDelta(String text) {
      events.add("delta:" + text);
    }

    @Override
    public void onDone(String status, String failureCode) {
      events.add("done:" + status + ":" + failureCode);
    }

    @Override
    public void onFailure(String code) {
      events.add("error:" + code);
    }
  }

  @Test
  void subscriberReceivesBufferedTextThenLiveDeltas() {
    UUID question = UUID.randomUUID();
    streams.delta(question, "先到的");
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    streams.delta(question, "后到的");
    assertThat(recorder.events).containsExactly("delta:先到的", "delta:后到的");
  }

  @Test
  void finishDeliversDoneAndStopsFurtherDeltas() {
    UUID question = UUID.randomUUID();
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    streams.delta(question, "正文");
    streams.finish(question, "SUCCEEDED", null);
    streams.delta(question, "迟到");
    assertThat(recorder.events).containsExactly("delta:正文", "done:SUCCEEDED:null");
  }

  @Test
  void lateSubscriberOfFinishedAnswerSeesBufferedTextThenResult() {
    UUID question = UUID.randomUUID();
    streams.delta(question, "完整回答");
    streams.finish(question, "FAILED", "MODEL_UNAVAILABLE");
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    // 回答瞬间结束时，随后连上的客户端也能先看到文本，前端照常逐字显示。
    assertThat(recorder.events)
        .containsExactly("delta:完整回答", "done:FAILED:MODEL_UNAVAILABLE");
  }

  @Test
  void hasTracksChannelUntilPurged() {
    UUID question = UUID.randomUUID();
    assertThat(streams.has(question)).isFalse();
    streams.finish(question, "SUCCEEDED", null);
    assertThat(streams.has(question)).isTrue();
    streams.purgeExpired(System.currentTimeMillis() + AnswerStreams.RETAIN_AFTER_FINISH_MS + 1);
    assertThat(streams.has(question)).isFalse();
  }

  @Test
  void failureIsReportedToSubscribers() {
    UUID question = UUID.randomUUID();
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    streams.fail(question, "INTERNAL_ERROR");
    assertThat(recorder.events).containsExactly("error:INTERNAL_ERROR");
  }

  @Test
  void unsubscribedClientStopsReceivingDeltas() {
    UUID question = UUID.randomUUID();
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    streams.unsubscribe(question, recorder);
    streams.delta(question, "不该收到");
    assertThat(recorder.events).isEmpty();
  }

  @Test
  void finishedChannelIsRetainedThenPurged() {
    UUID question = UUID.randomUUID();
    streams.finish(question, "SUCCEEDED", null);
    long now = System.currentTimeMillis();
    streams.purgeExpired(now);
    assertThat(streams.size()).isEqualTo(1);
    streams.purgeExpired(now + AnswerStreams.RETAIN_AFTER_FINISH_MS + 1);
    assertThat(streams.size()).isZero();
  }

  @Test
  void bufferedTextIsCappedForLateSubscribers() {
    UUID question = UUID.randomUUID();
    streams.delta(question, "甲".repeat(AnswerStreams.MAX_BUFFERED_CHARS + 10));
    Recorder recorder = new Recorder();
    streams.subscribe(question, recorder);
    assertThat(recorder.events).hasSize(1);
    String replay = recorder.events.get(0).substring("delta:".length());
    assertThat(replay.length()).isLessThanOrEqualTo(AnswerStreams.MAX_BUFFERED_CHARS);
  }
}
