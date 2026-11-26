CREATE INDEX ix_job_kind_ready ON job(job_type, available_at, id) WHERE status = 'QUEUED';
CREATE INDEX ix_job_kind_active ON job(job_type, status) WHERE status IN ('QUEUED', 'RUNNING');
