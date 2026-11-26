-- Preserve the requested model ID even if the config is removed before the answer job runs.
ALTER TABLE question ADD COLUMN requested_model_id UUID;
