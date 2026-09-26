-- =====================================================================
--  NOTICE MODE  —  one notice per customer, or one per bill
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    Auction notices are grouped by customer: a customer with three
--    overdue bills gets one notice listing all three. Some shops need
--    the opposite — one notice per bill — because the notice is read as
--    a per-bill legal notice.
--
--    Both layouts were already built into the notice printer; nothing
--    chose between them. The grouping happened automatically whenever
--    two bills shared a customer, so a shop that wanted separate
--    notices had no way to ask for them.
--
--  WHERE IT LIVES
--    On the company row, beside allow_to_change_rep_exp_date and the
--    other company-wide flags — not in company_other_settings, which is
--    per material and would let GOLD and SILVER disagree about how a
--    customer's notice reads.
--
--    It is set in Company Master, which only an administrator reaches,
--    so it stays a house policy rather than something changed per run.
--
--  DEFAULT
--    FALSE — one notice per customer, exactly what every shop does
--    today. Nothing changes until an administrator switches it.
-- =====================================================================


ALTER TABLE company
    ADD COLUMN IF NOT EXISTS notice_one_per_bill BOOLEAN NOT NULL DEFAULT FALSE;


-- Check what you have -------------------------------------------------
SELECT id,
       name,
       notice_one_per_bill,
       CASE WHEN notice_one_per_bill THEN 'One notice per BILL'
            ELSE 'One notice per CUSTOMER (default)'
       END AS notice_mode
  FROM company
 ORDER BY id;
