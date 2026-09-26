-- =====================================================================
--  KARUMBALAI — IMAGE + BACKUP FILE MIGRATION (verify / force re-upload)
--
--  IMPORTANT: images & backups are FILES ON DISK, not database rows.
--  The Pawnbroking Sync Agent uploads them automatically — there is no
--  "INSERT" to run. This script only:
--    (a) shows the folders the agent will scan (STEP 1 & 2), and
--    (b) clears the "already uploaded" trackers so the agent re-scans and
--        re-uploads EVERY file from scratch (STEP 4 — only needed to
--        FORCE a re-upload; a brand-new setup does not need it).
--
--  PREREQUISITES (must be true or nothing uploads):
--    1. The Sync Agent service is RUNNING and on the LATEST jar
--       (the one in ShopInstallerBundle\3-SyncAgent\).
--    2. The cloud has karumbalai's Magizhchi box token — i.e. someone has
--       logged into the karumbalai shop on the phone (email+OTP) at least
--       once. Without it the cloud returns 503 for every image.
--    3. The image + backup folders below actually EXIST on THIS PC with
--       files in them (a DB restore does NOT copy image/backup files —
--       they must be COPIED from the old machine separately).
--
--  HOW TO RUN  (karumbalai shop PC, pgAdmin, database "pawnbroking"):
--    Open this file -> Execute (F5). Read the Messages/Data-output tabs.
--    Then leave the PC on; the agent uploads over the next minutes/hours.
-- =====================================================================


-- ── STEP 1 — Show the IMAGE root(s) the agent scans ──────────────────
-- Open these paths in File Explorer and confirm they exist with files
-- laid out as  <root>\<CMP>\GOLD\<billNo>\<image>.png  (and CUSTOMERS\..).
SELECT company_id, camera_temp_file_name AS image_root
FROM company_other_settings
WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> '';


-- ── STEP 2 — Show the BACKUP root(s) the agent scans ─────────────────
SELECT id AS company_id, backup_file_path AS backup_root
FROM company
WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> '';


-- ── STEP 3 — How many files has the agent already (thinks it) uploaded?
SELECT 'images'  AS kind, count(*) AS uploaded FROM sync_image_uploads
UNION ALL
SELECT 'backups' AS kind, count(*) AS uploaded FROM sync_backup_uploads;


-- ── STEP 4 — FORCE A FULL RE-UPLOAD (only if you need it) ────────────
-- Skip this on a brand-new setup: the trackers are already empty, so the
-- agent uploads every file on its own. Run it ONLY to re-push everything
-- (e.g. after copying in more old files). Safe: the cloud de-duplicates
-- by (company, material, bill, image) — re-uploading refreshes, never
-- creates duplicates.
-- TRUNCATE sync_image_uploads;
-- TRUNCATE sync_backup_uploads;

-- Nudge the agent to start scanning immediately instead of waiting 60s.
NOTIFY sync_channel, 'rescan';


-- ── STEP 5 — (re-run this later to watch progress) ───────────────────
-- The numbers climb as the agent uploads. Images are throttled to
-- ~54/min for the box, so a big shop takes a while.
SELECT 'images'  AS kind, count(*) AS uploaded FROM sync_image_uploads
UNION ALL
SELECT 'backups' AS kind, count(*) AS uploaded FROM sync_backup_uploads;

-- =====================================================================
--  AFTER RUNNING:
--   • Watch the agent log on this PC for "uploaded"/"image scan" lines:
--       C:\Program Files\PawnbrokingSync\pawnbroking-sync.out.log
--     and errors in:
--       C:\Program Files\PawnbrokingSync\pawnbroking-sync.err.log
--   • If the err.log shows status=503 -> the box token is missing:
--       log into karumbalai on the phone once (email+OTP), then it resumes.
--   • If err.log shows status=401 -> wrong cloud.api_key in sync.properties.
--   • Verify on the cloud (Railway):
--       SELECT count(*) FROM karumbalai.bill_images;
--       SELECT count(*) FROM karumbalai.backup_files;
-- =====================================================================
