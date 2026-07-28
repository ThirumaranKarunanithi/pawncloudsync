-- =====================================================================
-- INITIAL FULL BACKFILL (composite-PK safe)
--
-- Pushes all EXISTING desktop rows to the cloud the first time a shop
-- is onboarded. Unlike a naive INSERT-into-outbox, this fires the LIVE
-- sync_capture trigger (V3), so composite primary keys (e.g.
-- company_billing = company_id|jewel_material_type|bill_number) get the
-- correct row_pk and DON'T collapse into one row on the cloud.
--
-- Only the tables the mobile app reads are touched — keeps the event
-- volume small and the drain fast.
--
-- HOW TO RUN (on the SHOP PC, in pgAdmin connected to the local DB):
--   1. Change 'alwarpuram' below to this shop's shop_id.
--   2. Make sure the sync agent service is STOPPED first (so it doesn't
--      drain a half-built queue) — optional but cleaner.
--   3. Run this whole script.
--   4. Start the sync agent; watch alwarpuram.projections fill on the cloud.
--
-- Safe to re-run.
-- =====================================================================

SET app.shop_id = 'alwarpuram';        -- ← change per shop

-- Clear any previously-queued (possibly bad) events first.
TRUNCATE sync_outbox;

DO $$
DECLARE
    t   TEXT;
    col TEXT;
BEGIN
    FOR t IN
        SELECT unnest(ARRAY[
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
        ])
    LOOP
        -- Skip tables that don't exist on this install.
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_name = t
        ) THEN
            RAISE NOTICE 'skip % (not present)', t;
            CONTINUE;
        END IF;

        -- First updatable (non-identity, non-generated) column — used for a
        -- no-op self-UPDATE that fires the AFTER UPDATE trigger on every row.
        SELECT column_name INTO col
        FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = t
          AND is_identity = 'NO' AND is_generated = 'NEVER'
        ORDER BY ordinal_position
        LIMIT 1;

        IF col IS NULL THEN
            RAISE NOTICE 'skip % (no updatable column)', t;
            CONTINUE;
        END IF;

        EXECUTE format('UPDATE %I SET %I = %I', t, col, col);
        RAISE NOTICE 'touched % via %', t, col;
    END LOOP;
END $$;

-- Wake the agent immediately.
NOTIFY sync_channel, 'backfill';
