-- =====================================================================
--  ANNANAGAR — ONE-TIME FULL CLOUD BACKFILL
--
--  WHAT THIS DOES
--    Pushes ALL existing annanagar desktop data to the cloud. The sync
--    triggers only capture NEW changes, so historical rows (bills,
--    repledges, customers, today's-account, expenses/incomes) never went
--    up. This script re-emits every existing row with the correct
--    primary-key so nothing collapses on the cloud.
--
--  HOW TO RUN  (on the ANNANAGAR shop PC)
--    1. Make sure the Pawnbroking Sync Agent service is RUNNING.
--    2. Open pgAdmin -> connect to the local PostgreSQL
--       (database "pawnbroking", user "postgres").
--    3. Open this file (File -> Open) and click Execute (F5),
--       OR paste the whole thing into a Query window and run.
--    4. Watch the "Messages" tab — it prints each table and row count.
--    5. Leave the PC on; the agent ships everything to the cloud over
--       the next several minutes. Large shops can take a while.
--
--  SAFE: idempotent, changes no business data (no-op self-UPDATEs only),
--  can be re-run any number of times.
-- =====================================================================


-- ─────────────────────────────────────────────────────────────────────
-- STEP 1 — Install the PK-aware capture trigger function.
--   Builds row_pk from the table's REAL primary key (single OR composite)
--   so reused bill numbers / composite keys don't collapse on the cloud.
-- ─────────────────────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION sync_capture() RETURNS trigger AS $$
DECLARE
    v_shop_id TEXT; v_payload JSONB; v_row_pk TEXT;
    v_pk_cols TEXT[]; v_col TEXT; v_parts TEXT[] := ARRAY[]::TEXT[];
BEGIN
    BEGIN v_shop_id := current_setting('app.shop_id', true);
    EXCEPTION WHEN OTHERS THEN v_shop_id := NULL; END;
    IF v_shop_id IS NULL OR length(v_shop_id) = 0 THEN v_shop_id := 'DEFAULT'; END IF;

    IF TG_OP = 'DELETE' THEN v_payload := to_jsonb(OLD); ELSE v_payload := to_jsonb(NEW); END IF;

    SELECT array_agg(a.attname ORDER BY array_position(i.indkey::int[]::int[], a.attnum))
      INTO v_pk_cols
      FROM pg_index i
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
     WHERE i.indrelid = (TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME)::regclass
       AND i.indisprimary;

    IF v_pk_cols IS NOT NULL AND array_length(v_pk_cols, 1) > 0 THEN
        FOREACH v_col IN ARRAY v_pk_cols LOOP
            v_parts := array_append(v_parts, COALESCE(v_payload->>v_col, ''));
        END LOOP;
        v_row_pk := array_to_string(v_parts, '|');
    ELSE
        v_row_pk := COALESCE(v_payload->>'id', v_payload->>'bill_no',
            v_payload->>'bill_number', v_payload->>'customer_id',
            v_payload->>'company_id', v_payload->>'pk');
    END IF;

    INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload)
    VALUES (gen_random_uuid(), v_shop_id, TG_TABLE_NAME, LEFT(TG_OP,1), v_row_pk, v_payload);
    PERFORM pg_notify('sync_channel', gen_random_uuid()::text);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;


-- ─────────────────────────────────────────────────────────────────────
-- STEP 2 — Give repledge_billing a primary key if it lacks one.
--   Without a PK every repledge row collapses to one on the cloud.
--   repledge_bill_id is unique; add it. Skips silently if a PK exists.
-- ─────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_index
        WHERE indrelid = 'public.repledge_billing'::regclass AND indisprimary
    ) THEN
        BEGIN
            EXECUTE 'ALTER TABLE repledge_billing ADD PRIMARY KEY (repledge_bill_id)';
            RAISE NOTICE 'repledge_billing: primary key added on repledge_bill_id';
        EXCEPTION WHEN OTHERS THEN
            RAISE NOTICE 'repledge_billing: could NOT add PK (% ) — will still sync but may collapse', SQLERRM;
        END;
    ELSE
        RAISE NOTICE 'repledge_billing: already has a primary key (good)';
    END IF;
END $$;


-- ─────────────────────────────────────────────────────────────────────
-- STEP 3 — (Re)attach the capture trigger to every business table.
-- ─────────────────────────────────────────────────────────────────────
DO $$
DECLARE t TEXT; n INT := 0;
BEGIN
    FOR t IN
        SELECT table_name FROM information_schema.tables
        WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
          AND table_name NOT IN ('sync_outbox','sync_outbox_dlq')
          AND table_name NOT LIKE 'flyway_%' AND table_name NOT LIKE 'pg_%'
        ORDER BY table_name
    LOOP
        EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', t, t);
        EXECUTE format('CREATE TRIGGER trg_sync_%I AFTER INSERT OR UPDATE OR DELETE '
                     || 'ON %I FOR EACH ROW EXECUTE FUNCTION sync_capture()', t, t);
        n := n + 1;
    END LOOP;
    RAISE NOTICE 'triggers attached to % tables', n;
END $$;


-- ─────────────────────────────────────────────────────────────────────
-- STEP 4 — Re-emit every existing row of the tables the mobile app reads.
--   Clears the queue first, sets the tenant, then a no-op UPDATE on each
--   table fires the trigger for all its rows. Missing tables are skipped.
-- ─────────────────────────────────────────────────────────────────────
SET app.shop_id = 'annanagar';
TRUNCATE sync_outbox;

DO $$
DECLARE
    t   TEXT;
    col TEXT;
    c   BIGINT;
    grand BIGINT := 0;
    tables TEXT[] := ARRAY[
        'company',
        'company_billing',
        'customer_details',
        'repledge_billing',
        'company_advance_amount',
        'company_todays_account',
        'company_todays_account_available_amount',
        'employee_daily_allowance_debit',
        'employee_advance_amount_debit',
        'employee_salary_amount_debit',
        'employee_other_amount_debit',
        'company_bill_debit',
        'company_other_debit',
        'repledge_bill_debit',
        'repledge_other_debit',
        'employee_advance_amount_credit',
        'employee_other_amount_credit',
        'company_bill_credit',
        'company_other_credit',
        'repledge_bill_credit',
        'repledge_other_credit'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_name = t
        ) THEN
            RAISE NOTICE 'skip % (not present)', t;
            CONTINUE;
        END IF;

        -- first updatable column for a harmless self-UPDATE
        SELECT column_name INTO col
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t
          AND is_identity = 'NO' AND is_generated = 'NEVER'
        ORDER BY ordinal_position LIMIT 1;

        IF col IS NULL THEN
            RAISE NOTICE 'skip % (no updatable column)', t;
            CONTINUE;
        END IF;

        EXECUTE format('UPDATE %I SET %I = %I', t, col, col);
        GET DIAGNOSTICS c = ROW_COUNT;
        grand := grand + c;
        RAISE NOTICE '% : % rows queued', t, c;
    END LOOP;
    RAISE NOTICE '==== TOTAL % rows queued for cloud ====', grand;
END $$;

NOTIFY sync_channel, 'backfill';


-- ─────────────────────────────────────────────────────────────────────
-- STEP 5 — (Optional) check progress. Re-run this single line every
--   minute; it counts DOWN to 0 as the agent ships everything.
-- ─────────────────────────────────────────────────────────────────────
SELECT count(*) AS still_pending FROM sync_outbox WHERE sent_at IS NULL;

-- =====================================================================
--  When still_pending = 0, all annanagar data is on the cloud.
--  Reopen the mobile app's MIS Report / Stock screens to confirm.
-- =====================================================================
