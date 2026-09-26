-- =====================================================================
-- ONE-TIME REPLAY: Re-emit every row of company_todays_account (and the
-- related profit / advance tables) so the V3 sync_capture trigger rebuilds
-- correct composite-PK events. The sync agent then ships these to the
-- cloud, populating mylocal.projections so the mobile app's Today's
-- Account screen can find them.
--
-- BACKGROUND:
--   The original sync_capture trigger COALESCEd a single PK column for
--   row_pk. For composite-PK tables that meant every row shared the same
--   row_pk and ON CONFLICT collapsed them all into one — effectively zero
--   rows after dedup. V3 fixes the trigger; this script re-fires it on
--   every existing row.
--
-- HOW TO RUN:
--   1. Open pgAdmin / psql connected to the DESKTOP database (the one
--      the sync agent watches).
--   2. Make sure the V3 migration ran (the sync agent applies it on
--      startup — restart the agent once if unsure).
--   3. Paste and run this whole script. It is idempotent and safe to
--      re-run.
--   4. Watch the sync agent's log — you should see ~N events flushed to
--      the cloud, where N is the row count of these tables combined.
--   5. Reopen the mobile app's Today's Account screen.
--
-- SAFETY:
--   - SET app.shop_id is per-session and per-transaction. Replace
--     'mylocal' with your tenant's shop_id if different.
--   - No data is modified — the UPDATE sets each row's company_id to
--     itself. The trigger sees this as an UPDATE and re-emits an event.
-- =====================================================================

BEGIN;

-- Identify this session as the right tenant so sync_capture stamps
-- the events with the right shop_id.
SET LOCAL app.shop_id = 'mylocal';

-- 1. Today's Account header (the L-marker rows the mobile app needs).
UPDATE company_todays_account
   SET company_id = company_id
 WHERE TRUE;

-- 2. Available-amount breakdown (where todays_pf_amount / profit lives —
--    drives the MIS report's profit column).
UPDATE company_todays_account_available_amount
   SET company_id = company_id
 WHERE TRUE;

-- 3. Advance amounts (composite PK collapsed too; needed for the
--    Today's Account operations breakdown once it's wired).
UPDATE company_advance_amount
   SET company_id = company_id
 WHERE TRUE;

COMMIT;

-- =====================================================================
-- VERIFY (run after the sync agent has had ~30s to flush):
--
--   In cloud DB (Railway):
--     SELECT count(*) FROM mylocal.projections
--      WHERE table_name = 'company_todays_account'
--        AND payload->>'ref_mark' = 'L';
--
--   Expected: > 0 (matches your desktop's L-row count).
-- =====================================================================
