CREATE TABLE app_user (
  id UUID PRIMARY KEY,
  issuer TEXT NOT NULL,
  subject TEXT NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  can_create_space BOOLEAN NOT NULL DEFAULT FALSE,
  auth_version BIGINT NOT NULL DEFAULT 0 CHECK (auth_version >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (issuer, subject)
);

CREATE TABLE user_role (
  user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role VARCHAR(32) NOT NULL CHECK (role IN ('USER', 'SYSTEM_ADMIN')),
  PRIMARY KEY (user_id, role)
);

CREATE TABLE space (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES app_user(id),
  name VARCHAR(120) NOT NULL,
  state_version BIGINT NOT NULL DEFAULT 0 CHECK (state_version >= 0),
  publication_epoch BIGINT NOT NULL DEFAULT 0 CHECK (publication_epoch >= 0),
  current_publication_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_space_owner ON space(owner_id, created_at DESC, id);

CREATE TABLE space_member (
  space_id UUID NOT NULL REFERENCES space(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  can_read BOOLEAN NOT NULL DEFAULT FALSE,
  can_maintain BOOLEAN NOT NULL DEFAULT FALSE,
  granted_by UUID NOT NULL REFERENCES app_user(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (space_id, user_id),
  CHECK (can_read OR can_maintain)
);
CREATE INDEX ix_space_member_user ON space_member(user_id, space_id);

CREATE TABLE document_version (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL REFERENCES space(id),
  normalized_filename VARCHAR(255) NOT NULL,
  display_filename VARCHAR(255) NOT NULL,
  object_key TEXT NOT NULL UNIQUE,
  sha256 CHAR(64) NOT NULL,
  media_type VARCHAR(120) NOT NULL,
  size_bytes BIGINT NOT NULL CHECK (size_bytes >= 0 AND size_bytes <= 104857600),
  scan_state VARCHAR(24) NOT NULL CHECK (scan_state IN ('PENDING', 'CLEAN', 'REJECTED')),
  parse_state VARCHAR(24) NOT NULL CHECK (parse_state IN ('QUEUED', 'PROCESSING', 'READY', 'NO_TEXT', 'FAILED')),
  try_count INTEGER NOT NULL DEFAULT 0 CHECK (try_count >= 0),
  error_code VARCHAR(64),
  created_by UUID NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (space_id, id),
  UNIQUE (space_id, normalized_filename)
);
CREATE INDEX ix_document_space_state ON document_version(space_id, parse_state, created_at DESC);

CREATE TABLE document_chunk (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL,
  document_version_id UUID NOT NULL,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  body TEXT NOT NULL,
  search_vector TSVECTOR NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (space_id, document_version_id) REFERENCES document_version(space_id, id) ON DELETE CASCADE,
  UNIQUE (document_version_id, ordinal),
  UNIQUE (document_version_id, id)
);
CREATE INDEX ix_document_chunk_vector ON document_chunk USING GIN(search_vector);
CREATE INDEX ix_document_chunk_document ON document_chunk(document_version_id, ordinal);

CREATE TABLE space_draft (
  space_id UUID PRIMARY KEY REFERENCES space(id) ON DELETE CASCADE,
  draft_revision BIGINT NOT NULL DEFAULT 0 CHECK (draft_revision >= 0),
  updated_by UUID NOT NULL REFERENCES app_user(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE space_draft_document (
  space_id UUID NOT NULL,
  document_version_id UUID NOT NULL,
  effective_from DATE,
  effective_until DATE,
  policy_state VARCHAR(16) NOT NULL CHECK (policy_state IN ('EFFECTIVE', 'EXPIRED', 'UNKNOWN')),
  replaces_document_version_id UUID,
  PRIMARY KEY (space_id, document_version_id),
  FOREIGN KEY (space_id) REFERENCES space_draft(space_id) ON DELETE CASCADE,
  FOREIGN KEY (space_id, document_version_id) REFERENCES document_version(space_id, id),
  FOREIGN KEY (space_id, replaces_document_version_id) REFERENCES document_version(space_id, id),
  CHECK (effective_from IS NULL OR effective_until IS NULL OR effective_from < effective_until),
  CHECK (replaces_document_version_id IS NULL OR replaces_document_version_id <> document_version_id)
);
CREATE INDEX ix_space_draft_document_replacement ON space_draft_document(space_id, replaces_document_version_id);

CREATE TABLE space_snapshot (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL REFERENCES space(id),
  source_draft_revision BIGINT NOT NULL CHECK (source_draft_revision >= 0),
  content_digest CHAR(64) NOT NULL,
  created_by UUID NOT NULL REFERENCES app_user(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (space_id, id)
);
CREATE TABLE space_snapshot_document (
  space_id UUID NOT NULL,
  snapshot_id UUID NOT NULL,
  document_version_id UUID NOT NULL,
  effective_from DATE,
  effective_until DATE,
  policy_state VARCHAR(16) NOT NULL CHECK (policy_state IN ('EFFECTIVE', 'EXPIRED', 'UNKNOWN')),
  replaces_document_version_id UUID,
  PRIMARY KEY (snapshot_id, document_version_id),
  FOREIGN KEY (space_id, snapshot_id) REFERENCES space_snapshot(space_id, id) ON DELETE CASCADE,
  FOREIGN KEY (space_id, document_version_id) REFERENCES document_version(space_id, id),
  FOREIGN KEY (space_id, replaces_document_version_id) REFERENCES document_version(space_id, id),
  CHECK (effective_from IS NULL OR effective_until IS NULL OR effective_from < effective_until),
  CHECK (replaces_document_version_id IS NULL OR replaces_document_version_id <> document_version_id)
);
CREATE INDEX ix_snapshot_document_version ON space_snapshot_document(document_version_id, snapshot_id);

CREATE TABLE publish_request (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL REFERENCES space(id),
  snapshot_id UUID NOT NULL,
  base_publication_id UUID,
  base_epoch BIGINT NOT NULL CHECK (base_epoch >= 0),
  status VARCHAR(32) NOT NULL CHECK (status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX', 'PUBLISHED', 'REJECTED', 'CANCELLED')),
  submitted_by UUID NOT NULL REFERENCES app_user(id),
  reviewed_by UUID REFERENCES app_user(id),
  submission_reason VARCHAR(2000) NOT NULL CHECK (length(btrim(submission_reason)) > 0),
  decision_reason VARCHAR(2000),
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  decided_at TIMESTAMPTZ,
  FOREIGN KEY (space_id, snapshot_id) REFERENCES space_snapshot(space_id, id),
  UNIQUE (space_id, id),
  UNIQUE (space_id, snapshot_id),
  CHECK (status <> 'REJECTED' OR length(btrim(COALESCE(decision_reason, ''))) > 0)
);
CREATE UNIQUE INDEX uq_publish_request_open_space ON publish_request(space_id)
  WHERE status IN ('PENDING_REVIEW', 'APPROVED_WAITING_INDEX');
CREATE INDEX ix_publish_request_submitter ON publish_request(submitted_by, submitted_at DESC, id);
CREATE INDEX ix_publish_request_status ON publish_request(status, submitted_at, id);

CREATE TABLE space_publication (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL,
  snapshot_id UUID NOT NULL,
  request_id UUID NOT NULL UNIQUE,
  epoch BIGINT NOT NULL CHECK (epoch >= 0),
  published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY (space_id, snapshot_id) REFERENCES space_snapshot(space_id, id),
  FOREIGN KEY (space_id, request_id) REFERENCES publish_request(space_id, id),
  UNIQUE (space_id, id),
  UNIQUE (space_id, epoch)
);
ALTER TABLE space ADD CONSTRAINT fk_space_current_publication
  FOREIGN KEY (id, current_publication_id) REFERENCES space_publication(space_id, id)
  DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX ix_publication_snapshot ON space_publication(space_id, snapshot_id, published_at DESC);

CREATE TABLE assistant (
  id UUID PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  answer_template TEXT,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE assistant_space (
  assistant_id UUID NOT NULL REFERENCES assistant(id) ON DELETE CASCADE,
  space_id UUID NOT NULL REFERENCES space(id) ON DELETE CASCADE,
  binding_role VARCHAR(32) NOT NULL CHECK (binding_role = 'RETRIEVAL'),
  PRIMARY KEY (assistant_id, space_id)
);
CREATE INDEX ix_assistant_space_space ON assistant_space(space_id, assistant_id);

CREATE TABLE conversation (
  id UUID PRIMARY KEY,
  owner_id UUID NOT NULL REFERENCES app_user(id),
  assistant_id UUID NOT NULL REFERENCES assistant(id),
  title VARCHAR(240),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (id, owner_id)
);
CREATE INDEX ix_conversation_owner_recent ON conversation(owner_id, updated_at DESC, id);
CREATE TABLE question (
  id UUID PRIMARY KEY,
  conversation_id UUID NOT NULL,
  user_id UUID NOT NULL REFERENCES app_user(id),
  request_key VARCHAR(255) NOT NULL,
  text VARCHAR(2000) NOT NULL CHECK (length(btrim(text)) > 0),
  requested_space_ids UUID[],
  status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
  failure_code VARCHAR(64),
  run_revision BIGINT NOT NULL DEFAULT 0 CHECK (run_revision >= 0),
  accepted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  finished_at TIMESTAMPTZ,
  FOREIGN KEY (conversation_id, user_id) REFERENCES conversation(id, owner_id),
  UNIQUE (user_id, request_key)
);
CREATE INDEX ix_question_user_period ON question(user_id, accepted_at, id);
CREATE INDEX ix_question_conversation ON question(conversation_id, accepted_at, id);
CREATE TABLE answer_paragraph (
  id UUID PRIMARY KEY,
  question_id UUID NOT NULL REFERENCES question(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  body TEXT NOT NULL,
  answer_kind VARCHAR(16) NOT NULL CHECK (answer_kind IN ('ANSWER', 'NO_EVIDENCE', 'EXPIRED', 'UNCERTAIN', 'CONFLICT')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (question_id, ordinal)
);
CREATE TABLE model_call_usage (
  call_id VARCHAR(160) PRIMARY KEY,
  question_id UUID NOT NULL REFERENCES question(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES app_user(id),
  input_tokens BIGINT CHECK (input_tokens IS NULL OR input_tokens >= 0),
  output_tokens BIGINT CHECK (output_tokens IS NULL OR output_tokens >= 0),
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (question_id, call_id)
);
CREATE INDEX ix_model_call_user_period ON model_call_usage(user_id, received_at, call_id);
CREATE TABLE model_call_evidence (
  call_id VARCHAR(160) NOT NULL REFERENCES model_call_usage(call_id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  space_id UUID NOT NULL,
  publication_id UUID NOT NULL,
  epoch BIGINT NOT NULL CHECK (epoch >= 0),
  document_version_id UUID NOT NULL,
  chunk_id UUID,
  PRIMARY KEY (call_id, ordinal),
  FOREIGN KEY (space_id, publication_id) REFERENCES space_publication(space_id, id),
  FOREIGN KEY (space_id, document_version_id) REFERENCES document_version(space_id, id),
  FOREIGN KEY (document_version_id, chunk_id) REFERENCES document_chunk(document_version_id, id)
);
CREATE INDEX ix_model_evidence_space_epoch ON model_call_evidence(space_id, epoch, call_id);
CREATE TABLE paragraph_evidence (
  paragraph_id UUID NOT NULL REFERENCES answer_paragraph(id) ON DELETE CASCADE,
  call_id VARCHAR(160) NOT NULL,
  ordinal INTEGER NOT NULL,
  PRIMARY KEY (paragraph_id, call_id, ordinal),
  FOREIGN KEY (call_id, ordinal) REFERENCES model_call_evidence(call_id, ordinal) ON DELETE CASCADE
);
CREATE INDEX ix_paragraph_evidence_call ON paragraph_evidence(call_id, ordinal, paragraph_id);
CREATE TABLE citation (
  id UUID PRIMARY KEY,
  paragraph_id UUID NOT NULL,
  call_id VARCHAR(160) NOT NULL,
  evidence_ordinal INTEGER NOT NULL,
  display_label VARCHAR(255) NOT NULL,
  FOREIGN KEY (paragraph_id, call_id, evidence_ordinal)
    REFERENCES paragraph_evidence(paragraph_id, call_id, ordinal) ON DELETE CASCADE,
  UNIQUE (paragraph_id, id)
);
CREATE INDEX ix_citation_paragraph ON citation(paragraph_id, id);

CREATE TABLE import_batch (
  id UUID PRIMARY KEY,
  space_id UUID NOT NULL REFERENCES space(id),
  created_by UUID NOT NULL REFERENCES app_user(id),
  status VARCHAR(16) NOT NULL CHECK (status IN ('PROCESSING', 'COMPLETED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ,
  UNIQUE (space_id, id)
);
CREATE INDEX ix_import_batch_owner ON import_batch(created_by, created_at DESC, id);
CREATE TABLE import_item (
  batch_id UUID NOT NULL,
  space_id UUID NOT NULL,
  ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
  submitted_filename VARCHAR(255) NOT NULL,
  normalized_filename VARCHAR(255) NOT NULL,
  status VARCHAR(24) NOT NULL CHECK (status IN ('QUEUED', 'PROCESSING', 'READY', 'NO_TEXT', 'FAILED', 'SKIPPED_DUPLICATE', 'REJECTED')),
  document_version_id UUID,
  error_code VARCHAR(64),
  PRIMARY KEY (batch_id, ordinal),
  FOREIGN KEY (space_id, batch_id) REFERENCES import_batch(space_id, id) ON DELETE CASCADE,
  FOREIGN KEY (space_id, document_version_id) REFERENCES document_version(space_id, id),
  CHECK ((status IN ('QUEUED', 'PROCESSING', 'READY', 'NO_TEXT', 'FAILED') AND document_version_id IS NOT NULL)
      OR (status IN ('SKIPPED_DUPLICATE', 'REJECTED') AND document_version_id IS NULL)),
  CHECK (status NOT IN ('NO_TEXT', 'FAILED', 'SKIPPED_DUPLICATE', 'REJECTED') OR error_code IS NOT NULL)
);

CREATE TABLE job (
  id UUID PRIMARY KEY,
  job_type VARCHAR(24) NOT NULL CHECK (job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT', 'PUBLISH_SNAPSHOT', 'ANSWER_QUESTION')),
  space_id UUID REFERENCES space(id),
  document_version_id UUID REFERENCES document_version(id),
  question_id UUID REFERENCES question(id),
  publish_request_id UUID REFERENCES publish_request(id),
  status VARCHAR(16) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
  available_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  lease_expires_at TIMESTAMPTZ,
  lease_token UUID,
  attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
  error_code VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (((job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT')) AND document_version_id IS NOT NULL)
      OR (job_type = 'PUBLISH_SNAPSHOT' AND publish_request_id IS NOT NULL)
      OR (job_type = 'ANSWER_QUESTION' AND question_id IS NOT NULL))
);
CREATE INDEX ix_job_pick ON job(status, available_at, id);
CREATE UNIQUE INDEX uq_job_parse_active ON job(document_version_id, job_type)
  WHERE job_type IN ('PARSE_DOCUMENT', 'INDEX_DOCUMENT') AND status IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX uq_job_publish_active ON job(publish_request_id)
  WHERE job_type = 'PUBLISH_SNAPSHOT' AND status IN ('QUEUED', 'RUNNING');
CREATE UNIQUE INDEX uq_job_question_active ON job(question_id)
  WHERE job_type = 'ANSWER_QUESTION' AND status IN ('QUEUED', 'RUNNING');

CREATE TABLE request_idempotency (
  user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  operation VARCHAR(120) NOT NULL,
  idempotency_key VARCHAR(255) NOT NULL,
  request_digest CHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  resource_id UUID,
  response_status SMALLINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (user_id, operation, idempotency_key),
  CHECK (expires_at > created_at),
  CHECK ((status = 'IN_PROGRESS' AND resource_id IS NULL AND response_status IS NULL)
      OR (status = 'COMPLETED' AND resource_id IS NOT NULL AND response_status BETWEEN 200 AND 299))
);
CREATE INDEX ix_idempotency_expiry ON request_idempotency(expires_at);

CREATE TABLE global_setting (
  singleton_id SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_id = 1),
  model_alias VARCHAR(120) NOT NULL,
  credential_ref VARCHAR(255) NOT NULL,
  template TEXT NOT NULL,
  revision BIGINT NOT NULL DEFAULT 0 CHECK (revision >= 0),
  updated_by UUID NOT NULL REFERENCES app_user(id),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE audit_event (
  id UUID PRIMARY KEY,
  actor_user_id UUID REFERENCES app_user(id),
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(64) NOT NULL,
  resource_id UUID NOT NULL,
  request_id VARCHAR(160) NOT NULL,
  details JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_resource ON audit_event(resource_type, resource_id, created_at, id);
CREATE INDEX ix_audit_actor_period ON audit_event(actor_user_id, created_at, id);

CREATE INDEX ix_snapshot_lookup ON space_snapshot_document(space_id, snapshot_id, document_version_id);
CREATE INDEX ix_space_publication_epoch ON space_publication(space_id, epoch DESC);
