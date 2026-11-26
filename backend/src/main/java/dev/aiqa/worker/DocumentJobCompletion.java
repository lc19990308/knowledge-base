package dev.aiqa.worker;

import dev.aiqa.persistence.JobRepository;
import dev.aiqa.persistence.JobRepository.DocumentOutcome;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Atomically applies a document job's business changes and its lease-qualified transition. */
@Component
public class DocumentJobCompletion {

  private final JobRepository jobs;
  private final TransactionTemplate transactions;

  public DocumentJobCompletion(JobRepository jobs, PlatformTransactionManager transactionManager) {
    this.jobs = jobs;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  boolean finish(JobRepository.Job job, DocumentOutcome outcome, int delaySeconds, String errorCode,
      Runnable businessUpdate) {
    return Boolean.TRUE.equals(transactions.execute(status -> {
      if (!jobs.lockDocumentLease(job)) {
        return false;
      }
      businessUpdate.run();
      if (!jobs.finishDocumentLease(job, outcome, delaySeconds, errorCode)) {
        status.setRollbackOnly();
        return false;
      }
      return true;
    }));
  }
}
