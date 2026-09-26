-- =====================================================================
--  TODAY'S ACCOUNT - WHICH CLOSED DAYS HAVE A WRONG DEFICIT?   (read only)
--
--  pgAdmin -> Query Tool on the "pawnbroking" database -> open this file
--  -> F5. It changes nothing; it only lists.
--
--  Why: before the 14-09-2026 app, typing the Available Balance on the
--  Today's Account screen counted each key twice (774164 typed was read as
--  7741644), so the Deficit box showed a wrong figure - and Close Account
--  saved whatever that box showed.
--
--  The right Deficit is always  Available - Actual.  Every row listed here
--  saved something else. To put them right, run
--  todays_account_deficit_fix.sql.
-- =====================================================================

SELECT company_id                                                        AS company,
       to_char(todays_date, 'DD-MM-YYYY')                                AS day,
       which,
       actual_amount                                                     AS actual,
       available_amount                                                  AS available,
       saved_deficit,
       right_deficit
  FROM (
        SELECT company_id, todays_date, 'this day' AS which,
               todays_actual_amount    AS actual_amount,
               todays_available_amount AS available_amount,
               todays_deficit_amount   AS saved_deficit,
               round((todays_available_amount - todays_actual_amount)::numeric, 2) AS right_deficit
          FROM company_todays_account
         WHERE abs(todays_deficit_amount - (todays_available_amount - todays_actual_amount)) >= 0.01
        UNION ALL
        SELECT company_id, todays_date, 'previous day, as carried to this day',
               pre_actual_amount, pre_available_amount, pre_deficit_amount,
               round((pre_available_amount - pre_actual_amount)::numeric, 2)
          FROM company_todays_account
         WHERE abs(pre_deficit_amount - (pre_available_amount - pre_actual_amount)) >= 0.01
       ) wrong
 ORDER BY company_id, todays_date, which DESC;

--  No rows = every closed day is right; nothing to do.
