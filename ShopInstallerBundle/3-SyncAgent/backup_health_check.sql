-- =====================================================================
--  BACKUP / SYNC HEALTH CHECK  —  run on the SHOP PC's local
--  "pawnbroking" database (pgAdmin or the Magizhchi DB Communicator).
--
--  Read-only. Run each statement one at a time if your console splits
--  on ';'. Tells you whether this shop's data, images and backups are
--  actually reaching the cloud.
-- =====================================================================


-- 1. WHERE the agent looks for backups and images on this PC.
--    Open both paths in File Explorer — they must exist and hold files.
--    (A database restore does NOT bring image/backup files with it.)
SELECT id AS company_id, backup_file_path AS backup_root
FROM company
WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> '';

SELECT company_id, camera_temp_file_name AS image_root
FROM company_other_settings
WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> '';


-- 2. HOW MUCH has been uploaded, split by kind.
--    ".backup" = the real database dumps, ".pdf" = report files.
--    A shop with 0 ".backup" rows is NOT protected off-site.
SELECT count(*) FILTER (WHERE file_name ILIKE '%.backup')            AS db_dumps,
       count(*) FILTER (WHERE file_name ILIKE '%.pdf')               AS pdf_reports,
       count(*) FILTER (WHERE file_name NOT ILIKE '%.backup'
                          AND file_name NOT ILIKE '%.pdf')           AS other_files,
       count(*)                                                      AS total_uploaded,
       pg_size_pretty(COALESCE(sum(size_bytes), 0))                  AS total_size
FROM sync_backup_uploads;


-- 3. IS IT STILL CURRENT?  The newest backup actually uploaded.
--    If "days_old" is more than 1-2, the shop PC's backup job or the
--    sync agent has stopped — investigate before you need the backup.
SELECT file_name,
       pg_size_pretty(size_bytes)                       AS size,
       mtime,
       date_part('day', now() - mtime)::int             AS days_old
FROM sync_backup_uploads
ORDER BY mtime DESC
LIMIT 10;


-- 4. IMAGES uploaded so far (compare with the file count on disk).
SELECT count(*) AS images_uploaded FROM sync_image_uploads;


-- 5. EVENT QUEUE — bills/customers waiting to go to the cloud.
--    Should sit at or near 0. A number that only grows means the agent
--    is stopped, offline, or being rejected by the cloud.
SELECT count(*) FILTER (WHERE sent_at IS NULL) AS pending_events,
       count(*)                                AS total_events
FROM sync_outbox;


-- 6. ANY EVENTS STUCK IN RETRY (cloud rejecting them)?
--    last_error tells you why. Empty result = healthy.
SELECT table_name, attempts, left(last_error, 120) AS error, count(*)
FROM sync_outbox
WHERE sent_at IS NULL AND attempts > 0
GROUP BY 1, 2, 3
ORDER BY 4 DESC
LIMIT 10;


-- =====================================================================
--  WHAT THE ANSWERS MEAN
--
--   db_dumps = 0            -> only PDFs are being uploaded. Either the
--                              backup folder holds no .backup files, or
--                              the desktop's backup job is failing (the
--                              old pg_dump bug). Install the latest
--                              desktop build and run a backup once.
--
--   days_old > 2            -> backups have stopped. Check the agent is
--                              running and that new files are appearing
--                              in backup_file_path.
--
--   pending_events climbing -> agent stopped / offline / cloud rejecting.
--                              See pawnbroking-sync.err.log.
--
--   status=502 in err.log   -> backup too large to proxy. Fixed by the
--                              gzip build (run update-agent.bat).
--   status=507 in err.log   -> the Magizhchi box account is FULL.
--   status=503 in err.log   -> no box token: OTP-login this shop once on
--                              the phone with a SINGLE-shop email.
-- =====================================================================
