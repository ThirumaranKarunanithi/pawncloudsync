-- =====================================================================
--  LOCAL CLOUD — 02  fill the tenant from the shop's own database
--
--  Reads the shop DB over dblink and writes <shop>.events and
--  <shop>.projections in the local cloud DB exactly as Railway would
--  hold them after a full backfill plus live sync.
--
--  READ-ONLY ON THE SHOP. It never writes to the shop database, never
--  touches sync_outbox and fires no trigger there, so the sync agent can
--  keep running and nothing new is queued for the real cloud. (The real
--  onboarding, 5-OneTimeSQL\initial_full_backfill.sql, works the other
--  way — it self-UPDATEs every row to fire the triggers — and would
--  ship the whole history to whatever cloud the agent points at.)
--
--  FAITHFUL, NOT CORRECTED. row_pk is computed with the same logic as
--  the live sync_capture() (agent migration V3): the table's primary
--  key columns joined by '|', or, when a table has no primary key, the
--  first of id / bill_no / bill_number / customer_id / company_id / pk
--  that exists. A row with none of those gets 'evt:' || event_id, which
--  is what SyncController does. Where that guess is not unique the
--  projection collapses, here exactly as it does on Railway. That is
--  the point: 03_verify.sql then shows which tables the cloud is losing.
--
--  FULL REFRESH. Each run empties events/projections/notifications for
--  this tenant and rebuilds them from the shop as it stands now.
--
--  Variables (build-local-cloud.ps1 passes both):
--    shop_id    tenant schema, e.g. iravathanallur
--    shop_conn  libpq string to the SHOP database for dblink
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

CREATE EXTENSION IF NOT EXISTS dblink;

-- dblink is SET BY NAME for the loop; drop a stale one from an aborted run.
DO $$
BEGIN
    IF 'lc' = ANY (COALESCE(dblink_get_connections(), ARRAY[]::text[])) THEN
        PERFORM dblink_disconnect('lc');
    END IF;
END $$;

CREATE TEMP TABLE lc_stage (
    seq      BIGSERIAL,
    payload  JSONB NOT NULL
);

DO $$
DECLARE
    v_schema  TEXT := current_setting('lc.schema');
    v_conn    TEXT := current_setting('lc.conn');
    v_tables  TEXT[];
    t         TEXT;
    v_pk      TEXT[];
    v_rows    BIGINT;
    v_keys    BIGINT;
    v_synth   BIGINT;
BEGIN
    PERFORM dblink_connect('lc', v_conn);

    -- The set Railway can ever receive: every shop table that carries a
    -- sync trigger. zz_* scratch tables are skipped — the agent's schema
    -- guard attaches triggers to them too, but they are not shop data.
    SELECT array_agg(table_name ORDER BY table_name) INTO v_tables
      FROM dblink('lc', $q$
            SELECT DISTINCT c.relname::text
              FROM pg_trigger tg
              JOIN pg_class c      ON c.oid = tg.tgrelid
              JOIN pg_namespace n  ON n.oid = c.relnamespace
             WHERE NOT tg.tgisinternal
               AND tg.tgname LIKE 'trg_sync_%'
               AND n.nspname = 'public'
               AND c.relname NOT LIKE 'zz\_%'
           $q$) AS x(table_name TEXT);

    IF v_tables IS NULL THEN
        RAISE EXCEPTION 'The shop database has no trg_sync_* triggers — is the sync agent installed there?';
    END IF;

    EXECUTE format('TRUNCATE %I.events, %I.projections, %I.notifications',
                   v_schema, v_schema, v_schema);
    DELETE FROM lc.load_log WHERE shop_id = v_schema;

    FOREACH t IN ARRAY v_tables LOOP

        -- Primary key columns in key order — the same pg_index walk V3 does.
        SELECT array_agg(col ORDER BY ord) INTO v_pk
          FROM dblink('lc', format($q$
                SELECT a.attname::text,
                       array_position(i.indkey::int[], a.attnum::int)
                  FROM pg_index i
                  JOIN pg_attribute a
                    ON a.attrelid = i.indrelid AND a.attnum = ANY (i.indkey)
                 WHERE i.indrelid = %L::regclass
                   AND i.indisprimary
               $q$, 'public.' || t)) AS x(col TEXT, ord INT);

        TRUNCATE lc_stage;
        EXECUTE format(
            'INSERT INTO lc_stage (payload) SELECT j::jsonb FROM dblink(%L, %L) AS x(j TEXT)',
            'lc', format('SELECT to_jsonb(r)::text FROM public.%I r', t));

        -- One event per row (op U — what the real backfill's self-UPDATE
        -- produces), and one projection per distinct row_pk with the last
        -- row winning, which is the order-dependent outcome of sequential
        -- ON CONFLICT upserts on the real cloud.
        EXECUTE format($f$
            WITH k AS MATERIALIZED (
                SELECT s.seq,
                       s.payload,
                       gen_random_uuid() AS event_id,
                       CASE WHEN cardinality($1::text[]) > 0 THEN
                            array_to_string(ARRAY(
                                SELECT COALESCE(s.payload ->> u.c, '')
                                  FROM unnest($1::text[]) WITH ORDINALITY AS u(c, o)
                                 ORDER BY u.o), '|')
                       ELSE COALESCE(s.payload ->> 'id',
                                     s.payload ->> 'bill_no',
                                     s.payload ->> 'bill_number',
                                     s.payload ->> 'customer_id',
                                     s.payload ->> 'company_id',
                                     s.payload ->> 'pk')
                       END AS raw_pk
                  FROM lc_stage s
            ),
            kk AS MATERIALIZED (
                SELECT seq, payload, event_id,
                       COALESCE(raw_pk, 'evt:' || event_id) AS row_pk,
                       (raw_pk IS NULL) AS synthetic
                  FROM k
            ),
            ev AS (
                INSERT INTO %1$I.events (event_id, table_name, op, row_pk, payload, created_at)
                SELECT event_id, %2$L, 'U', row_pk, payload, now() FROM kk
                RETURNING 1
            )
            INSERT INTO %1$I.projections (table_name, row_pk, payload, last_op, last_event_id, deleted)
            SELECT DISTINCT ON (row_pk) %2$L, row_pk, payload, 'U', event_id, FALSE
              FROM kk
             ORDER BY row_pk, seq DESC
            ON CONFLICT (table_name, row_pk) DO UPDATE SET
                payload         = EXCLUDED.payload,
                last_op         = EXCLUDED.last_op,
                last_event_id   = EXCLUDED.last_event_id,
                last_updated_at = now(),
                deleted         = FALSE
        $f$, v_schema, t) USING v_pk;

        EXECUTE format($f$
            SELECT count(*),
                   count(DISTINCT row_pk) FILTER (WHERE row_pk NOT LIKE 'evt:%%'),
                   count(*)               FILTER (WHERE row_pk LIKE 'evt:%%')
              FROM %I.events WHERE table_name = %L
        $f$, v_schema, t) INTO v_rows, v_keys, v_synth;

        INSERT INTO lc.load_log (shop_id, table_name, pk_used, shop_rows, distinct_keys, synthetic_keys)
        VALUES (v_schema, t,
                COALESCE(array_to_string(v_pk, '|'), '(no primary key - guessed)'),
                v_rows, v_keys, v_synth);
    END LOOP;

    PERFORM dblink_disconnect('lc');
END $$;

-- What was pulled, largest first.
SELECT table_name, pk_used, shop_rows
  FROM lc.load_log
 WHERE shop_id = current_setting('lc.schema')
 ORDER BY shop_rows DESC, table_name;
