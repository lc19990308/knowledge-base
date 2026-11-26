package dev.aiqa.common;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Per-user fixed windows in this process. Shared enforcement belongs at the gateway. */
@Component
public class RateLimiter {

  private static final long WINDOW_MS = 60_000;
  private final Map<Key, Window> windows = new ConcurrentHashMap<>();
  private final MeterRegistry meters;
  private final int writesPerMinute;
  private final int questionsPerMinute;
  private final boolean enforceReads;
  private final Map<Read, Integer> readLimits;

  public enum Read {
    SEARCH("search"), USAGE("usage"), STATUS("status");

    private final String key;

    Read(String key) {
      this.key = key;
    }
  }

  public RateLimiter(
      MeterRegistry meters,
      @Value("${aiqa.rate-limit.writes-per-minute:120}") int writesPerMinute,
      @Value("${aiqa.rate-limit.questions-per-minute:30}") int questionsPerMinute,
      @Value("${aiqa.rate-limit.reads.enforce:false}") boolean enforceReads,
      @Value("${aiqa.rate-limit.reads.search-per-minute:120}") int searchesPerMinute,
      @Value("${aiqa.rate-limit.reads.usage-per-minute:120}") int usagePerMinute,
      @Value("${aiqa.rate-limit.reads.status-per-minute:180}") int statusPerMinute) {
    this.meters = meters;
    this.writesPerMinute = writesPerMinute;
    this.questionsPerMinute = questionsPerMinute;
    this.enforceReads = enforceReads;
    this.readLimits = Map.of(Read.SEARCH, searchesPerMinute, Read.USAGE, usagePerMinute, Read.STATUS, statusPerMinute);
  }

  public void checkWrites(UUID userId) {
    check(new Key("writes", userId), writesPerMinute, true);
  }

  public void checkQuestions(UUID userId) {
    check(new Key("questions", userId), questionsPerMinute, true);
  }

  public <T> T read(Read kind, UUID userId, Supplier<T> action) {
    check(new Key(kind.key, userId), readLimits.get(kind), enforceReads);
    return Timer.builder("aiqa.read.latency")
        .tag("operation", kind.key)
        .publishPercentiles(0.95)
        .register(meters)
        .record(action);
  }

  public void purgeExpired() {
    long now = System.currentTimeMillis();
    windows.forEach((key, window) -> {
      if (now - window.startedAt >= WINDOW_MS) {
        windows.remove(key, window);
      }
    });
  }

  private void check(Key key, int limit, boolean enforce) {
    long now = System.currentTimeMillis();
    Window window = windows.compute(key, (ignored, current) -> {
      if (current == null || now - current.startedAt >= WINDOW_MS) {
        return new Window(now, 1);
      }
      return new Window(current.startedAt, current.count + 1);
    });
    boolean exceeded = window.count > limit;
    meters.counter("aiqa.rate.limit.requests", "operation", key.operation,
        "outcome", exceeded ? (enforce ? "rejected" : "would_reject") : "within_limit").increment();
    if (exceeded && enforce) {
      long retryAfter = Math.max(1, (window.startedAt + WINDOW_MS - now + 999) / 1000);
      throw new ApiException(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
          "请求过于频繁，请稍后重试", retryAfter);
    }
  }

  private record Key(String operation, UUID userId) {}

  private record Window(long startedAt, long count) {}
}
