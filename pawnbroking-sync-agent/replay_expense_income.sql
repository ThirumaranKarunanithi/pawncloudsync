-- =====================================================================
-- ONE-TIME REPLAY: find the desktop's expense/income table by name,
-- (re-)attach the sync_capture trigger so future writes ship to cloud,
-- then no-op UPDATE every row so the V3 trigger fires and the projection
-- lands on the cloud's mylocal.projections.
--
-- After this finishes:
--   - The EXPENSES and INCOMES rows on the mobile Today's Account screen
--     should populate from real data instead of showing 0/0/0.
--
-- HOW TO RUN:
--   1. Open pgAdmin / psql connected to the DESKTOP database (the one
--      the sync agent watches).
--   2. Paste and run this whole script. It's idempotent.
--   3. Wait ~30s and reopen Today's Account on the phone.
--
-- The script discovers tables matching common pawnbroking naming
-- conventions: "expense", "income", "entry", "voucher".
-- =====================================================================

DO $$
DECLARE
    v_table   TEXT;
    v_count   BIGINT;
    v_pattern TEXT;
    v_total_replayed BIGINT := 0;
    v_total_tables   INT    := 0;
BEGIN
    -- Identify ourselves as the right tenant so sync_capture stamps
    -- events with the correct shop_id. Change 'mylocal' if needed.
    PERFORM set_config('app.shop_id', 'mylocal', false);

    FOR v_table IN
        SELECT table_name
          FROM information_schema.tables
         WHERE table_schema = 'public'
           AND table_type   = 'BASE TABLE'
           AND ( table_name ILIKE '%expense%'
              OR table_name ILIKE '%income%'
              OR table_name ILIKE '%entry%'
              OR table_name ILIKE '%voucher%' )
           AND table_name NOT LIKE 'sync_%'
           AND table_name NOT LIKE 'flyway_%'
         ORDER BY table_name
    LOOP
        -- How many rows does this candidate hold?
        EXECUTE format('SELECT count(*) FROM %I', v_table) INTO v_count;
        RAISE NOTICE 'found table: % (% rows)', v_table, v_count;

        IF v_count = 0 THEN
            RAISE NOTICE '  -> skipped (empty)';
            CONTINUE;
        END IF;

        -- Re-attach sync trigger (drops any existing one with the same
        -- name first — same idempotent pattern as V2__attach_all_triggers).
        BEGIN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', v_table, v_table);
            EXECUTE format(
                'CREATE TRIGGER trg_sync_%I
                 AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH ROW EXECUTE FUNCTION sync_capture()',
                 v_table, v_table);
            RAISE NOTICE '  -> trigger attached';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE '  -> trigger attach FAILED: %', SQLERRM;
            CONTINUE;
        END;

        -- Pick any column to no-op UPDATE — using ctid keeps it neutral
        -- (always present, never null) regardless of the table's columns.
        BEGIN
            EXECUTE format('UPDATE %I SET ctid = ctid', v_table);
            v_total_replayed := v_total_replayed + v_count;
            v_total_tables   := v_total_tables   + 1;
            RAISE NOTICE '  -> replayed % rows', v_count;
        EXCEPTION WHEN OTHERS THEN
            -- ctid pseudo-column UPDATE doesn't work on all PG versions;
            -- fall back to picking the first updatable column.
            DECLARE
                v_col TEXT;
            BEGIN
                SELECT column_name INTO v_col
                  FROM information_schema.columns
                 WHERE table_schema = 'public'
                   AND table_name   = v_table
                 ORDER BY ordinal_position
                 LIMIT 1;
                EXECUTE format('UPDATE %I SET %I = %I', v_table, v_col, v_col);
                v_total_replayed := v_total_replayed + v_count;
                v_total_tables   := v_total_tables   + 1;
                RAISE NOTICE '  -> replayed % rows (via column %)', v_count, v_col;
            EXCEPTION WHEN OTHERS THEN
                RAISE NOTICE '  -> replay FAILED: %', SQLERRM;
            END;
        END;
    END LOOP;

    RAISE NOTICE '==== replayed % rows across % table(s) ====',
                 v_total_replayed, v_total_tables;
    IF v_total_tables = 0 THEN
        RAISE NOTICE 'No expense/income table found. Run this to inspect ALL tables:';
        RAISE NOTICE '  SELECT table_name FROM information_schema.tables';
        RAISE NOTICE '   WHERE table_schema = ''public'' ORDER BY table_name;';
    END IF;
END $$;

-- =====================================================================
-- VERIFY (run after the sync agent has had ~30s to flush):
--
--   In cloud DB (Railway):
--     SELECT table_name, count(*)
--       FROM mylocal.projections
--      WHERE table_name ILIKE '%expense%' OR table_name ILIKE '%income%'
--         OR table_name ILIKE '%entry%'   OR table_name ILIKE '%voucher%'
--      GROUP BY table_name;
--
--   Expected: at least one row with > 0 count.
--
-- THEN tell Claude the table_name that appeared, plus its columns
-- (run the same jsonb_object_keys query as before with the table_name).
-- Claude will plug that name into the cloud query and you're done.
-- =====================================================================
