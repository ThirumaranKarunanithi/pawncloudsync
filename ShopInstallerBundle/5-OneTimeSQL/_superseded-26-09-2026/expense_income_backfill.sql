-- =====================================================================
--  EXPENSE / INCOME BACKFILL  — run once per shop on the shop PC
--
--  WHY: Today's Account EXPENSES and INCOMES are summed by the desktop
--  from 14 ledger tables (8 debit + 6 credit). The earlier full-backfill
--  only re-emitted company_other_debit / company_other_credit, so the
--  other 12 tables' *historical* rows never reached the cloud — the
--  mobile figures were short. (New rows already sync; this only replays
--  the existing history.)
--
--  WHAT IT DOES: makes sure the capture trigger is on each of the 14
--  tables, then does a no-op self-UPDATE on every row so the trigger
--  ships it to the cloud. Changes NO business data. Does NOT truncate
--  the outbox, so anything already pending is untouched.
--
--  HOW TO RUN  (shop PC)
--    1. Sync Agent service must be RUNNING.
--    2. pgAdmin -> local PostgreSQL, database "pawnbroking".
--    3. >>> EDIT the shop id on the  SET app.shop_id  line below <<<
--       ('balamurugan', 'mylocal', 'annanagar', 'alwarpuram', ...)
--    4. Execute (F5). Watch the Messages tab for per-table counts.
--    5. Leave the PC on; rows upload over the next few minutes.
--
--  Idempotent — safe to run again.
-- =====================================================================

-- The 14 tables the desktop's getAllExpenses/IncomeAccountValues read.
-- (company_other_debit/credit are included for completeness — harmless.)

-- STEP 1 — make sure the capture trigger is attached to each table.
DO $$
DECLARE t TEXT;
    tables TEXT[] := ARRAY[
        'employee_daily_allowance_debit','employee_advance_amount_debit',
        'employee_salary_amount_debit','employee_other_amount_debit',
        'company_bill_debit','company_other_debit',
        'repledge_bill_debit','repledge_other_debit',
        'employee_advance_amount_credit','employee_other_amount_credit',
        'company_bill_credit','company_other_credit',
        'repledge_bill_credit','repledge_other_credit'];
    n INT := 0;
BEGIN
    FOREACH t IN ARRAY tables LOOP
        IF EXISTS (SELECT 1 FROM information_schema.tables
                   WHERE table_schema=current_schema() AND table_name=t) THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', t, t);
            EXECUTE format('CREATE TRIGGER trg_sync_%I AFTER INSERT OR UPDATE OR DELETE '
                         || 'ON %I FOR EACH ROW EXECUTE FUNCTION sync_capture()', t, t);
            n := n + 1;
        END IF;
    END LOOP;
    RAISE NOTICE 'trigger ensured on % expense/income table(s)', n;
END $$;


-- STEP 2 — re-emit every existing row (no-op self-UPDATE fires the trigger).
SET app.shop_id = 'balamurugan';        -- <<< EDIT: this shop's id

DO $$
DECLARE t TEXT; col TEXT; c BIGINT; grand BIGINT := 0;
    tables TEXT[] := ARRAY[
        'employee_daily_allowance_debit','employee_advance_amount_debit',
        'employee_salary_amount_debit','employee_other_amount_debit',
        'company_bill_debit','company_other_debit',
        'repledge_bill_debit','repledge_other_debit',
        'employee_advance_amount_credit','employee_other_amount_credit',
        'company_bill_credit','company_other_credit',
        'repledge_bill_credit','repledge_other_credit'];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        IF NOT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema=current_schema() AND table_name=t) THEN
            RAISE NOTICE 'skip % (not present)', t; CONTINUE;
        END IF;
        SELECT column_name INTO col FROM information_schema.columns
        WHERE table_schema=current_schema() AND table_name=t
          AND is_identity='NO' AND is_generated='NEVER'
        ORDER BY ordinal_position LIMIT 1;
        IF col IS NULL THEN RAISE NOTICE 'skip % (no updatable column)', t; CONTINUE; END IF;
        EXECUTE format('UPDATE %I SET %I = %I', t, col, col);
        GET DIAGNOSTICS c = ROW_COUNT; grand := grand + c;
        RAISE NOTICE '% : % rows queued', t, c;
    END LOOP;
    RAISE NOTICE '==== TOTAL % expense/income rows queued for cloud ====', grand;
END $$;

NOTIFY sync_channel, 'ei-backfill';


-- STEP 3 — watch it drain (re-run until it hits 0).
SELECT count(*) AS still_pending FROM sync_outbox WHERE sent_at IS NULL;

-- VERIFY on the cloud (Railway) after it drains — every table should appear:
--   SELECT table_name, count(*) FROM <shop>.projections
--    WHERE table_name LIKE '%_debit' OR table_name LIKE '%_credit'
--    GROUP BY table_name ORDER BY table_name;
