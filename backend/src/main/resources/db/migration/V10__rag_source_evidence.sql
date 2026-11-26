ALTER TABLE document_chunk ADD COLUMN source_location TEXT;

ALTER TABLE model_call_evidence
  ADD COLUMN evidence_excerpt TEXT,
  ADD COLUMN source_location TEXT;

-- Freeze any legacy chunk references before a rebuild can replace the indexed rows.
UPDATE model_call_evidence evidence
SET evidence_excerpt = chunk.body,
    source_location = chunk.source_location,
    chunk_id = NULL
FROM document_chunk chunk
WHERE chunk.document_version_id = evidence.document_version_id
  AND chunk.id = evidence.chunk_id;
