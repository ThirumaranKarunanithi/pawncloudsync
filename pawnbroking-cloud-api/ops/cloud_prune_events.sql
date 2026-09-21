-- =====================================================================
--  PRUNE OLD RAW EVENTS  (one shop at a time)
--  Run on Railway -> Postgres -> Data -> Query, ONE statement at a time.
--
--  WHAT THIS TOUCHES
--     <shop>.events        the raw log of every sync message ever sent.
--     <shop>.notifications the bell list on the phone.
--  Neither is what the app reads for bills, customers, stock or Today's
--  Account - that is <shop>.projections, and this file never touches it.
--  Pruning changes no figure anywhere in the phone app.
--
--  WHAT YOU LOSE
--     The ability to replay history from raw events, and a date-tamper
--     investigation older than the window you keep. If that matters for
--     a particular shop, keep 365 days for it instead of 180.
--
--  READ THIS BEFORE RUNNING IT AT 90%+ FULL
--     DELETE does not give the space back to the disk. It marks rows
--     dead; VACUUM then lets Postgres REUSE that room for new rows. The
--     volume usage bar will NOT fall. It stops the growth - it does not
--     undo it.
--     The only things that shrink the file are VACUUM FULL (which needs
--     as much free space as the table is big - impossible at 91%) and a
--     dump/restore. So: RESIZE THE VOLUME FIRST, then prune, then
--     reclaim with P4 once there is room to work in.
-- =====================================================================


-- P1  How much would go, before anything goes. Change the shop name in
--     all three places. 180 days keeps two full quarters.
SELECT 'alwarpuram' AS shop,
       count(*) AS events_now,
       count(*) FILTER (WHERE received_at < now() - interval '180 days') AS would_delete,
       pg_size_pretty(pg_total_relation_size('alwarpuram.events'))       AS table_size
  FROM alwarpuram.events;


-- P2  Delete in batches, not in one go. One huge DELETE writes one huge
--     transaction of WAL, which on a nearly full volume is how you turn
--     a warning into an outage. Run this statement again and again until
--     it reports 0 rows; each run takes a few seconds.
DELETE FROM alwarpuram.events
 WHERE ctid IN (
     SELECT ctid FROM alwarpuram.events
      WHERE received_at < now() - interval '180 days'
      LIMIT 50000);


-- P3  The bell list. It is per-notification and nobody reads one from
--     last year; 90 days is generous.
DELETE FROM alwarpuram.notifications
 WHERE ctid IN (
     SELECT ctid FROM alwarpuram.notifications
      WHERE created_at < now() - interval '90 days'
      LIMIT 50000);


-- P4  Make the freed room reusable. Plain VACUUM: safe, needs no spare
--     space, does not lock the table against the shops.
VACUUM (ANALYZE) alwarpuram.events;


-- P5  Only when the volume has room to work in (after a resize), and
--     only if you want the FILE to shrink. It rewrites the table, so it
--     needs free space the size of the table and it takes an exclusive
--     lock for the duration - do it when nothing is syncing.
-- VACUUM FULL alwarpuram.events;


-- P6  Check what it bought you.
SELECT n.nspname AS schema, c.relname AS table,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total
  FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r' AND c.relname IN ('events','projections','notifications')
 ORDER BY pg_total_relation_size(c.oid) DESC;


-- =====================================================================
--  THEN STOP IT COMING BACK
--  Pruning by hand every few months is a reminder nobody keeps. The
--  cloud API can do it on a schedule instead - ask for it and it goes in
--  as a nightly job with the window as an environment variable.
-- =====================================================================
