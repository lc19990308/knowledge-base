package dev.aiqa.worker;

import dev.aiqa.answer.AnswerService;
import dev.aiqa.answer.AnswerStreams;
import dev.aiqa.conversation.QuestionRepository;
import dev.aiqa.persistence.JobRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AnswerWorker {

  private static final Logger log = LoggerFactory.getLogger(AnswerWorker.class);

  private final JobRepository jobs;
  private final AnswerService answers;
  private final QuestionRepository questions;
  private final AnswerStreams streams;

  public AnswerWorker(
      JobRepository jobs,
      AnswerService answers,
      QuestionRepository questions,
      AnswerStreams streams) {
    this.jobs = jobs;
    this.answers = answers;
    this.questions = questions;
    this.streams = streams;
  }

  @Scheduled(fixedDelayString = "${aiqa.worker.poll-ms:800}")
  public void poll() {
    try {
      for (int index = 0; index < 3; index++) {
        JobRepository.Job job = jobs.claimNext(JobRepository.Kind.ANSWER, 180);
        if (job == null) {
          break;
        }
        UUID token = job.leaseToken();
        try {
          boolean handled = answers.answer(job.questionId());
          jobs.succeed(job.id(), token);
          // 只有真正执行了这条问题的 worker 才收尾增量流；重复领取不能把别人的流关掉。
          if (handled) {
            finishStream(job.questionId());
          }
        } catch (Exception ex) {
          log.warn("问答任务失败 job={}", job.id(), ex);
          jobs.failAnswer(job.id(), token);
          streams.fail(job.questionId(), "INTERNAL_ERROR");
        }
      }
    } catch (Exception ex) {
      log.warn("问答任务轮询异常", ex);
    }
  }

  /**
   * 回答事务结束后再通知增量流结束，客户端收到的 done 一定是数据库已经提交的状态；
   * 事务已回滚或状态不是终态时按出错处理，让客户端回退轮询。
   */
  private void finishStream(UUID questionId) {
    QuestionRepository.QuestionRow row = questions.findAny(questionId).orElse(null);
    if (row == null) {
      streams.fail(questionId, "NOT_FINISHED");
      return;
    }
    if ("SUCCEEDED".equals(row.status()) || "FAILED".equals(row.status())) {
      streams.finish(questionId, row.status(), row.failureCode());
    } else {
      streams.fail(questionId, "NOT_FINISHED");
    }
  }
}
