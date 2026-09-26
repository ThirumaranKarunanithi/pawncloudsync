-- =====================================================================
-- ONE-TIME REPLAY: company_other_debit (EXPENSES) and
-- company_other_credit (INCOMES) — these tables hold the non-bill
-- cash movements that the Today's Account screen shows as EXPENSES /
-- INCOMES rows. Same composite-PK trigger fix as the other replays.
--
-- Run on the DESKTOP database (the one the sync agent watches).
-- Idempotent — safe to re-run.
-- =====================================================================

BEGIN;

-- Make the trigger stamp events with the right tenant.
SET LOCAL app.shop_id = 'mylocal';

-- EXPENSES source
UPDATE company_other_debit
   SET company_id = company_id
 WHERE TRUE;

-- INCOMES source
UPDATE company_other_credit
   SET company_id = company_id
 WHERE TRUE;

COMMIT;

-- Verify on the cloud DB after ~30s:
--   SELECT table_name, count(*)
--     FROM mylocal.projections
--    WHERE table_name IN ('company_other_debit','company_other_credit')
--    GROUP BY table_name;
