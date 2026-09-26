-- =====================================================================
--  TODAY'S ACCOUNT - PUT WRONG DEFICITS RIGHT                  (2026-09-14)
--
--  pgAdmin -> Query Tool on the "pawnbroking" database -> open this file
--  -> F5. The table it shows at the end lists every figure it corrected.
--  Press F5 again whenever you like: a second run finds nothing to do.
--
--  Why: before the 14-09-2026 app, typing the Available Balance on the
--  Today's Account screen counted each key twice (774164 typed was read as
--  7741644), so the Deficit box showed a wrong figure - and Close Account
--  saved it. Install the new app first, or the next close saves it again.
--
--  What it changes: ONLY the two deficit columns of company_todays_account,
--  and only where they are not  Available - Actual  (to the paise).
--  The Actual and Available amounts are never touched. All of it is one
--  transaction: it either all happens or none of it does.
--
--  Want to look first? todays_account_deficit_check.sql lists the same
--  rows and changes nothing.
-- =====================================================================

BEGIN;

-- 1. What is wrong, remembered for the report at the end.
DROP TABLE IF EXISTS pg_temp.deficit_fixed;
CREATE TEMP TABLE deficit_fixed AS
SELECT company_id, todays_date, 'this day'::text AS which,
       todays_actual_amount    AS actual_amount,
       todays_available_amount AS available_amount,
       todays_deficit_amount   AS was,
       round((todays_available_amount - todays_actual_amount)::numeric, 2)::double precision AS now_is
  FROM company_todays_account
 WHERE abs(todays_deficit_amount - (todays_available_amount - todays_actual_amount)) >= 0.01
UNION ALL
SELECT company_id, todays_date, 'previous day, as carried to this day',
       pre_actual_amount, pre_available_amount, pre_deficit_amount,
       round((pre_available_amount - pre_actual_amount)::numeric, 2)::double precision
  FROM company_todays_account
 WHERE abs(pre_deficit_amount - (pre_available_amount - pre_actual_amount)) >= 0.01;

-- 2. Put them right.
UPDATE company_todays_account
   SET todays_deficit_amount = round((todays_available_amount - todays_actual_amount)::numeric, 2)
 WHERE abs(todays_deficit_amount - (todays_available_amount - todays_actual_amount)) >= 0.01;

UPDATE company_todays_account
   SET pre_deficit_amount = round((pre_available_amount - pre_actual_amount)::numeric, 2)
 WHERE abs(pre_deficit_amount - (pre_available_amount - pre_actual_amount)) >= 0.01;

-- 3. The phone. company_todays_account has no primary key, so the cloud
--    keeps ONE row per company - the last one it was sent. The updates
--    above send older days; touching each company's latest closed day
--    (ref_mark 'L') last sends that one again, so the phone still shows
--    the latest day. Changes no value.
UPDATE company_todays_account
   SET ref_mark = ref_mark
 WHERE ref_mark = 'L'
   AND EXISTS (SELECT 1 FROM deficit_fixed);

COMMIT;

-- 4. The report.
SELECT company_id                         AS company,
       to_char(todays_date, 'DD-MM-YYYY') AS day,
       which,
       actual_amount                      AS actual,
       available_amount                   AS available,
       was                                AS deficit_was,
       now_is                             AS deficit_now
  FROM deficit_fixed
 ORDER BY company_id, todays_date, which DESC;

--  No rows = nothing was wrong; nothing was changed.
