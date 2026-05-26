-- =====================================================================
-- One-time BACKFILL: capture every existing row from every business
-- table into sync_outbox so the sync-agent pushes them to cloud.
--
-- Run this ONCE after a fresh DB restore, when you want existing rows
-- to appear in the cloud (not just future changes).
--
-- Safe to re-run: each existing outbox entry has a unique event_id,
-- and the cloud's ON CONFLICT (event_id) DO NOTHING deduplicates.
-- =====================================================================

DO $$
DECLARE
    t           TEXT;
    pk_col      TEXT;
    sql         TEXT;
    rows_added  BIGINT;
    total_added BIGINT := 0;
    shop_id     TEXT;
BEGIN
    -- Resolve shop_id from the session/db default
    BEGIN
        shop_id := current_setting('app.shop_id', true);
    EXCEPTION WHEN OTHERS THEN
        shop_id := NULL;
    END;
    IF shop_id IS NULL OR length(shop_id) = 0 THEN
        shop_id := 'DEFAULT';
    END IF;
    RAISE NOTICE 'backfilling with shop_id = %', shop_id;

    FOR t IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_type   = 'BASE TABLE'
          AND table_name NOT IN ('sync_outbox', 'sync_outbox_dlq')
          AND table_name NOT LIKE 'flyway_%'
          AND table_name NOT LIKE 'pg_%'
          AND table_name <> 'spatial_ref_sys'
        ORDER BY table_name
    LOOP
        -- find this table's primary key column (first one, if composite)
        SELECT a.attname INTO pk_col
        FROM pg_index i
        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
        WHERE i.indrelid = (current_schema() || '.' || t)::regclass
          AND i.indisprimary
        ORDER BY a.attnum
        LIMIT 1;

        IF pk_col IS NULL THEN
            -- no PK → use NULL row_pk; payload still captured
            sql := format(
                'INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload) ' ||
                'SELECT gen_random_uuid(), %L, %L, ''I'', NULL, to_jsonb(t) FROM %I t',
                shop_id, t, t);
        ELSE
            sql := format(
                'INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload) ' ||
                'SELECT gen_random_uuid(), %L, %L, ''I'', (t.%I)::text, to_jsonb(t) FROM %I t',
                shop_id, t, pk_col, t);
        END IF;

        EXECUTE sql;
        GET DIAGNOSTICS rows_added = ROW_COUNT;
        total_added := total_added + rows_added;
        RAISE NOTICE '% : % rows', t, rows_added;
    END LOOP;

    RAISE NOTICE '---- backfill complete: % total events queued ----', total_added;
END $$;

-- Wake the agent immediately instead of waiting for the next poll
NOTIFY sync_channel, 'backfill';
