package dev.aiqa.worker;

import dev.aiqa.answer.AnswerStreams;
import dev.aiqa.persistence.IdempotencyRepository;
import dev.aiqa.persistence.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Housekeeping that must not race user traffic; safe to run on every instance. */
@Component
public class MaintenanceWorker {

  private static final Logger log = LoggerFactory.getLogger(MaintenanceWorker.class);

  private final IdempotencyRepository idempotency;
  private final JobRepository jobs;
  private final dev.aiqa.common.RateLimiter limiter;
  private final io.micrometer.core.instrument.MeterRegistry meters;
  private final AnswerStreams streams;

  public MaintenanceWorker(
      IdempotencyRepository idempotency,
      JobRepository jobs,
      dev.aiqa.common.RateLimiter limiter,
      io.micrometer.core.instrument.MeterRegistry meters,
      AnswerStreams streams) {
    this.idempotency = idempotency;
    this.jobs = jobs;
    this.limiter = limiter;
    this.meters = meters;
    this.streams = streams;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.maintenance-ms:60000}", initialDelayString = "15000")
  public void run() {
    try {
      idempotency.purgeExpired();
      limiter.purgeExpired();
      streams.purgeExpired();
      jobs.failExpiredAnswers();
      jobs.requeueExpiredLeases();
      meters.gauge("aiqa.job.queued", jobs, repository -> repository.queuedCount());
    } catch (Exception ex) {
      log.warn("维护任务执行失败", ex);
    }
  }
}
