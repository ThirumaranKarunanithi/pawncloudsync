-- =====================================================================
--  MANONMANI - CLOUD CHECK AFTER THE HISTORY HAS SYNCED
--  Run on Railway -> your cloud service -> Data -> Query.
--
--  V1 and V2 compare the cloud with the desktop - run them once
--  manonmani_setup.sql on the shop PC shows "Waiting to send" = 0.
--  V3 shows what has ARRIVED so far - run it any time. How much is still
--  LEFT is only known on the shop PC: 3-SyncAgent\upload-progress.ps1.
--  Read-only; safe to run as often as you like.
--
--  "relation manonmani.projections does not exist" means the schema is
--  not there yet: add manonmani to the TENANTS variable (R4 of
--  manonmani_cloud_provision.sql) and wait for the redeploy.
-- =====================================================================


-- V1  What the cloud holds. company_billing, repledge_billing and
--     customer_details should match the "Desktop rows" lines of the shop
--     PC's report exactly. company_advance_amount and
--     company_todays_account will NOT: those tables have no primary key,
--     so the cloud keeps one row per bill / per company instead of one
--     per payment / per day. That is true at every shop today, and the
--     full history is kept in manonmani.events, so it can be rebuilt
--     once those tables get real keys.
SELECT table_name, count(*) AS cloud_rows
  FROM manonmani.projections
 WHERE NOT deleted
   AND table_name IN ('company_billing','repledge_billing','company_advance_amount',
                      'company_todays_account','customer_details')
 GROUP BY table_name ORDER BY table_name;


-- V2  Must return NO rows. A repledge saved at the counter after the agent
--     went in but before the shop PC's F5 went up under the collapsed key
--     (the company id, e.g. CMP1) instead of its own repledge_bill_id, and
--     would show on the phone as one extra repledge.
SELECT row_pk, payload->>'repledge_bill_id' AS repledge_bill_id
  FROM manonmani.projections
 WHERE table_name = 'repledge_billing' AND NOT deleted
   AND row_pk IS DISTINCT FROM payload->>'repledge_bill_id'
 LIMIT 20;

--     If it returned rows (the real repledges are keyed by repledge_bill_id):
-- DELETE FROM manonmani.projections WHERE table_name = 'repledge_billing' AND row_pk IS DISTINCT FROM payload->>'repledge_bill_id';


-- V3  What has arrived so far, and when the latest of each came in
--     (Indian time). "photo key" must say yes, or no photo or backup
--     can arrive at all.
SELECT 'data rows' AS what, count(*)::text AS arrived,
       to_char(max(last_updated_at) AT TIME ZONE 'Asia/Kolkata', 'DD-MM-YYYY HH24:MI') AS latest
  FROM manonmani.projections WHERE NOT deleted
UNION ALL
SELECT 'bill photos', count(*)::text,
       to_char(max(uploaded_at) AT TIME ZONE 'Asia/Kolkata', 'DD-MM-YYYY HH24:MI')
  FROM manonmani.bill_images WHERE material_type <> 'CUSTOMER'
UNION ALL
SELECT 'customer photos', count(*)::text,
       to_char(max(uploaded_at) AT TIME ZONE 'Asia/Kolkata', 'DD-MM-YYYY HH24:MI')
  FROM manonmani.bill_images WHERE material_type = 'CUSTOMER'
UNION ALL
SELECT 'backup files', count(*) || ' (' || pg_size_pretty(COALESCE(sum(file_size_bytes), 0)) || ')',
       to_char(max(uploaded_at) AT TIME ZONE 'Asia/Kolkata', 'DD-MM-YYYY HH24:MI')
  FROM manonmani.backup_files
UNION ALL
SELECT 'photo key', CASE WHEN magizhchi_token IS NOT NULL THEN 'yes'
                         ELSE 'NO - sign in once on the phone as marunganathan@gmail.com' END, NULL
  FROM public.tenants WHERE shop_id = 'manonmani';
