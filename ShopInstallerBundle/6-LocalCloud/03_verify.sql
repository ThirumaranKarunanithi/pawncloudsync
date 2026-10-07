-- =====================================================================
--  LOCAL CLOUD — 03  compare the mirror with the shop, table by table
--
--  Re-counts independently: rows in the SHOP (over dblink), events and
--  live projections in the mirror. Every table gets one verdict:
--
--    complete     one projection per shop row — the cloud sees it all
--    COLLAPSED    rows share a row_pk, so projections keep only the last
--                 one written. The app reading projections sees fewer
--                 rows than the shop has. This is happening on Railway.
--    NO KEY       no primary key and none of the guessed columns, so
--                 every event becomes its own row keyed 'evt:<id>'. An
--                 update adds a row instead of replacing one, and a
--                 delete never takes effect.
--    MISMATCH     events do not match the shop — the load itself is
--                 incomplete. Re-run build-local-cloud.ps1.
--
--  events is never affected by any of this: every change is kept there
--  with its full payload, which is why the collapsed rows are
--  recoverable once each table has a real key.
--  PSQL ONLY — run it through build-local-cloud.ps1, not pgAdmin. pgAdmin
--  cannot read \set / \gset / :'variable', and stops on the first line
--  with 'syntax error at or near "on"'. When that happens nothing in the
--  file has run: PostgreSQL rejects the whole batch before executing any
--  of it.
-- =====================================================================

\set ON_ERROR_STOP on

-- LOCAL MACHINE ONLY. Refuses any server that is not this machine, so the
-- file can never run against Railway — where the load step's TRUNCATE
-- would wipe the real tenant's event log. Nothing below runs if it fails.
DO $guard$
BEGIN
    IF COALESCE(host(inet_server_addr()), 'local') NOT IN ('local', '127.0.0.1', '::1') THEN
        RAISE EXCEPTION 'Refused: connected to % (database %). This is the LOCAL cloud mirror script and only runs against PostgreSQL on this machine. Nothing was changed.',
            host(inet_server_addr()), current_database();
    END IF;
END $guard$;

SELECT set_config('lc.schema', :'shop_id',   false) AS schema_set \gset
SELECT set_config('lc.conn',   :'shop_conn', false) AS conn_set   \gset

CREATE TEMP TABLE lc_verify (
    table_name   TEXT,
    pk_used      TEXT,
    shop_rows    BIGINT,
    events       BIGINT,
    projections  BIGINT,
    synthetic    BIGINT
);

DO $$
DECLARE
    v_schema TEXT := current_setting('lc.schema');
    v_conn   TEXT := current_setting('lc.conn');
    r        RECORD;
    v_shop   BIGINT;
    v_ev     BIGINT;
    v_proj   BIGINT;
    v_syn    BIGINT;
BEGIN
    IF 'lcv' = ANY (COALESCE(dblink_get_connections(), ARRAY[]::text[])) THEN
        PERFORM dblink_disconnect('lcv');
    END IF;
    PERFORM dblink_connect('lcv', v_conn);

    FOR r IN SELECT table_name, pk_used FROM lc.load_log WHERE shop_id = v_schema LOOP
        SELECT n INTO v_shop
          FROM dblink('lcv', format('SELECT count(*) FROM public.%I', r.table_name)) AS x(n BIGINT);

        EXECUTE format('SELECT count(*) FROM %I.events WHERE table_name = %L',
                       v_schema, r.table_name) INTO v_ev;
        EXECUTE format('SELECT count(*), count(*) FILTER (WHERE row_pk LIKE ''evt:%%'')
                          FROM %I.projections WHERE table_name = %L AND NOT deleted',
                       v_schema, r.table_name) INTO v_proj, v_syn;

        INSERT INTO lc_verify VALUES (r.table_name, r.pk_used, v_shop, v_ev, v_proj, v_syn);
    END LOOP;

    PERFORM dblink_disconnect('lcv');
END $$;


-- ── The tenant, as provisioned ─────────────────────────────────────────
SELECT 'tenant'  AS what, shop_id || '  (' || display_name || ')' AS value
  FROM public.tenants WHERE shop_id = current_setting('lc.schema')
UNION ALL
SELECT 'sign-in', email || '  ' || role
  FROM public.user_shop_access WHERE shop_id = current_setting('lc.schema') AND revoked_at IS NULL
UNION ALL
SELECT 'sync key', label
  FROM public.shop_credentials WHERE shop_id = current_setting('lc.schema') AND revoked_at IS NULL;


-- ── Every table, worst first ────────────────────────────────────────────
SELECT table_name,
       pk_used,
       shop_rows,
       projections                                     AS cloud_sees,
       CASE
         WHEN events <> shop_rows THEN 'MISMATCH: load incomplete'
         WHEN synthetic > 0       THEN 'NO KEY: updates add rows, deletes never apply'
         WHEN projections < shop_rows
                                  THEN 'COLLAPSED: ' || (shop_rows - projections) || ' of ' || shop_rows || ' rows hidden'
         ELSE 'complete'
       END                                             AS verdict
  FROM lc_verify
 ORDER BY CASE
            WHEN events <> shop_rows        THEN 0
            WHEN projections < shop_rows    THEN 1
            WHEN synthetic > 0              THEN 2
            ELSE 3
          END,
          (shop_rows - projections) DESC,
          shop_rows DESC;


-- ── One line to read first ──────────────────────────────────────────────
SELECT count(*)                                              AS tables,
       count(*) FILTER (WHERE events = shop_rows
                          AND synthetic = 0
                          AND projections = shop_rows)       AS complete,
       count(*) FILTER (WHERE projections < shop_rows
                          AND synthetic = 0)                 AS collapsed,
       count(*) FILTER (WHERE synthetic > 0)                 AS no_key,
       count(*) FILTER (WHERE events <> shop_rows)           AS load_incomplete,
       sum(shop_rows)                                        AS shop_rows,
       sum(projections)                                      AS cloud_sees
  FROM lc_verify;
