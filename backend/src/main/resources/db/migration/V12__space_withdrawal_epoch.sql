ALTER TABLE space
  ADD COLUMN last_withdrawal_epoch BIGINT NOT NULL DEFAULT 0
    CHECK (last_withdrawal_epoch BETWEEN 0 AND publication_epoch);

-- A normal publish consumes one epoch and records a publication row. A withdrawal
-- consumes an epoch without a row. Treat any gap conservatively as a withdrawal.
WITH history AS (
  SELECT space_id, epoch, lag(epoch, 1, 0::bigint) OVER (PARTITION BY space_id ORDER BY epoch) AS previous_epoch
  FROM space_publication
), last_gap AS (
  SELECT space_id, max(epoch - 1) AS epoch
  FROM history
  WHERE epoch - previous_epoch > 1
  GROUP BY space_id
)
UPDATE space s
SET last_withdrawal_epoch = CASE
  WHEN s.current_publication_id IS NULL THEN s.publication_epoch
  ELSE coalesce((SELECT epoch FROM last_gap WHERE space_id = s.id), 0)
END
WHERE s.publication_epoch > 0;

-- Keep recording withdrawals even if an older application instance still writes
-- the pointer during a rollout.
CREATE FUNCTION record_space_withdrawal_epoch() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  NEW.last_withdrawal_epoch := NEW.publication_epoch;
  RETURN NEW;
END;
$$;

CREATE TRIGGER space_withdrawal_epoch
  BEFORE UPDATE OF current_publication_id ON space
  FOR EACH ROW
  WHEN (OLD.current_publication_id IS NOT NULL AND NEW.current_publication_id IS NULL)
  EXECUTE FUNCTION record_space_withdrawal_epoch();
