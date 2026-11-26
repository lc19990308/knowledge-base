package dev.aiqa.worker;

import dev.aiqa.persistence.JobRepository;
import dev.aiqa.publish.PublishService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PublishWorker {

  private static final Logger log = LoggerFactory.getLogger(PublishWorker.class);

  private final JobRepository jobs;
  private final PublishService publishes;
  private final io.micrometer.core.instrument.MeterRegistry meters;

  public PublishWorker(
      JobRepository jobs, PublishService publishes, io.micrometer.core.instrument.MeterRegistry meters) {
    this.jobs = jobs;
    this.publishes = publishes;
    this.meters = meters;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < 3; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.PUBLISH, 120);
        if (job == null) {
          break;
        }
        UUID token = job.leaseToken();
        try {
          boolean published = publishes.publishIfStillValid(job.publishRequestId());
          if (published) {
            jobs.succeed(job.id(), token);
          } else {
            meters.counter("aiqa.publish.waiting_index").increment();
            jobs.retryLater(job.id(), token, 2, "INDEX_NOT_READY");
          }
        } catch (Exception ex) {
          log.warn("发布任务失败 job={}", job.id(), ex);
          jobs.retryLater(job.id(), token, 5, "DEPENDENCY_UNAVAILABLE");
        }
      }
    } catch (Exception ex) {
      log.warn("发布任务轮询异常", ex);
    }
  }
}
