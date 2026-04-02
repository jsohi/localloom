-- V3__cleanup_duplicates_add_unique_index.sql
-- Remove duplicate INDEXED content units (same source + externalId) from before dedup fix.
-- Keep the earliest (lowest id) and delete later duplicates.

DELETE FROM content_units a
  USING content_units b
  WHERE a.id > b.id
    AND a.source_id = b.source_id
    AND a.external_id = b.external_id
    AND a.external_id IS NOT NULL
    AND a.status = 'INDEXED'
    AND b.status = 'INDEXED';

-- Partial unique index: prevents future duplicates for INDEXED episodes within a source.
-- Allows multiple non-INDEXED entries (PENDING, ERROR, etc.) for retry support.
CREATE UNIQUE INDEX IF NOT EXISTS idx_content_units_source_external_indexed
  ON content_units (source_id, external_id)
  WHERE external_id IS NOT NULL AND status = 'INDEXED';
