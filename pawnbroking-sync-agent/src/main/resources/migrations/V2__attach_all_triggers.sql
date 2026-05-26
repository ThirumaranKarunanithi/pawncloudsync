-- =====================================================================
-- V2: Auto-attach the sync_capture trigger to EVERY user table in the
-- public schema. Safe to re-run.
--
-- Skips:
--   - our infrastructure (sync_outbox, sync_outbox_dlq)
--   - Flyway / PostGIS / other system tables
--   - views, materialised views, foreign tables
-- =====================================================================

DO $$
DECLARE
    t TEXT;
    skipped INT := 0;
    attached INT := 0;
BEGIN
    FOR t IN
        SELECT table_name
        FROM information_schema.tables
        WHERE table_schema = current_schema()
          AND table_type   = 'BASE TABLE'
          AND table_name NOT IN ('sync_outbox', 'sync_outbox_dlq')
          AND table_name NOT LIKE 'flyway_%'
          AND table_name NOT LIKE 'pg_%'
          AND table_name NOT LIKE 'spatial_ref_sys'
        ORDER BY table_name
    LOOP
        BEGIN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', t, t);
            EXECUTE format(
                'CREATE TRIGGER trg_sync_%I
                 AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH ROW EXECUTE FUNCTION sync_capture()',
                 t, t);
            RAISE NOTICE 'attached: %', t;
            attached := attached + 1;
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'skipped % (error: %)', t, SQLERRM;
            skipped := skipped + 1;
        END;
    END LOOP;
    RAISE NOTICE '---- attached % triggers, skipped % ----', attached, skipped;
END $$;
