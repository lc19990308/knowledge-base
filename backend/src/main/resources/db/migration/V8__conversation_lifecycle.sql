ALTER TABLE conversation
  ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN deleted_at TIMESTAMPTZ;

CREATE INDEX ix_conversation_owner_archive_recent
  ON conversation(owner_id, archived, updated_at DESC, id)
  WHERE deleted_at IS NULL;

DROP INDEX ix_conversation_owner_recent;
