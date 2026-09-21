-- =====================================================================
--  WHAT IS FILLING THE RAILWAY POSTGRES VOLUME
--  Run on Railway -> Postgres -> Data -> Query.
--  Run each statement ONE AT A TIME (the console splits on ';').
--  Every statement here READS. Nothing is changed by this file.
--
--  Read them in order: S1 says how bad it is, S2 and S3 say what is
--  taking the room, S4 says whether it is old history or live data, and
--  S5 and S6 catch the two things that fill a volume without anybody
--  adding data.
-- =====================================================================


-- S1  The whole database, and how it splits by schema (= by shop).
--     Compare the total with the volume size Railway shows.
SELECT COALESCE(n.nspname, 'TOTAL')                   AS schema,
       pg_size_pretty(sum(pg_total_relation_size(c.oid))) AS size,
       round(100.0 * sum(pg_total_relation_size(c.oid))
             / NULLIF(sum(sum(pg_total_relation_size(c.oid))) OVER (), 0), 1) AS pct
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
 GROUP BY ROLLUP (n.nspname)
 ORDER BY sum(pg_total_relation_size(c.oid)) DESC NULLS FIRST;


-- S2  The 20 biggest tables anywhere. On this cloud the answer is almost
--     always <shop>.events: it keeps EVERY sync message for ever, while
--     projections only keeps the current row.
SELECT n.nspname AS schema, c.relname AS table,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total,
       pg_size_pretty(pg_relation_size(c.oid))       AS table_only,
       pg_size_pretty(pg_indexes_size(c.oid))        AS indexes,
       to_char(c.reltuples, 'FM999,999,999')         AS approx_rows
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
 WHERE c.relkind = 'r'
   AND n.nspname NOT IN ('pg_catalog', 'information_schema')
 ORDER BY pg_total_relation_size(c.oid) DESC
 LIMIT 20;


-- S3  Dead rows waiting to be cleaned up. A big n_dead_tup with an old
--     last_autovacuum means the space is already used but not reusable -
--     autovacuum has not caught up, which is common right after a big
--     backfill.
SELECT schemaname AS schema, relname AS table,
       n_live_tup AS live_rows, n_dead_tup AS dead_rows,
       to_char(last_autovacuum, 'DD-MM-YYYY HH24:MI') AS last_autovacuum,
       to_char(last_autoanalyze, 'DD-MM-YYYY HH24:MI') AS last_autoanalyze
  FROM pg_stat_user_tables
 WHERE n_dead_tup > 10000
 ORDER BY n_dead_tup DESC
 LIMIT 20;


-- S4  How old the events actually are, per shop. This is the statement
--     that decides whether pruning is worth anything: if most events are
--     from the first backfill months ago, they are pure history.
--     Repeat it per shop - change the schema name in both places.
SELECT 'alwarpuram' AS shop,
       count(*)                                        AS events,
       to_char(min(received_at), 'DD-MM-YYYY')         AS oldest,
       to_char(max(received_at), 'DD-MM-YYYY')         AS newest,
       count(*) FILTER (WHERE received_at < now() - interval '90 days')  AS older_than_90_days,
       count(*) FILTER (WHERE received_at < now() - interval '180 days') AS older_than_180_days
  FROM alwarpuram.events;


-- S5  WAL. A volume can fill with write-ahead log alone if something is
--     holding it - a replication slot left behind by a backup tool is the
--     usual reason. Anything over a few hundred MB here, or a slot that
--     is not active, needs dealing with before pruning anything.
SELECT (SELECT pg_size_pretty(sum(size)) FROM pg_ls_waldir())                       AS wal_on_disk,
       (SELECT count(*) FROM pg_replication_slots)                                  AS replication_slots,
       (SELECT count(*) FROM pg_replication_slots WHERE NOT active)                 AS inactive_slots,
       (SELECT current_setting('max_wal_size'))                                     AS max_wal_size;


-- S6  Any slot that is not active is pinning WAL for ever. If one shows
--     here and nothing uses it, dropping it frees that space at once:
--        SELECT pg_drop_replication_slot('<slot_name>');
SELECT slot_name, slot_type, active,
       pg_size_pretty(pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn)) AS wal_held
  FROM pg_replication_slots
 ORDER BY pg_wal_lsn_diff(pg_current_wal_lsn(), restart_lsn) DESC;


-- =====================================================================
--  WHAT TO DO WITH THE ANSWERS
--
--  An inactive replication slot (S5/S6)
--      Drop it. The space comes back by itself, and nothing else needed.
--
--  <shop>.events is most of it (S2), and most of them are old (S4)
--      Prune it - see cloud_prune_events.sql. projections is what the
--      phone reads; events is the raw log behind it. Pruning does not
--      change one figure in the app.
--
--  Dead rows are most of it (S3)
--      VACUUM (not FULL) the table named. That makes the space reusable
--      without needing any free space to work in.
--
--  It is all live data
--      Then the volume is simply too small: resize it in Railway. That
--      is the only honest answer, and it is instant.
--
--  DO NOT run VACUUM FULL at 91%. It writes a whole new copy of the
--  table before dropping the old one, so it needs as much free space as
--  the table is big - at 91% that is exactly what there is not.
-- =====================================================================
