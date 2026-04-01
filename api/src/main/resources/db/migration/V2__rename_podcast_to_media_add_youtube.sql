-- V2__rename_podcast_to_media_add_youtube.sql
-- Rename PODCAST source type to MEDIA for the generalised connector model.
-- No constraint change needed — source_type is VARCHAR(50).

UPDATE sources SET source_type = 'MEDIA' WHERE source_type = 'PODCAST';
