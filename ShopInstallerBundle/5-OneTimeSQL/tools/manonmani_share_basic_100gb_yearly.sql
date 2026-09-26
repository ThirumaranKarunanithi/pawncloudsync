-- =====================================================================
--  MANONMANI - MAGIZHCHI SHARE: BASIC 100 GB, YEARLY (paid)
--  Account: marunganathan@gmail.com
--
--  Run on the MAGIZHCHI SHARE database (Railway -> the Share service's
--  Postgres -> Data -> Query) - NOT the pawnbroking cloud. On the wrong
--  database these statements just fail: it has no users / plans tables.
--
--  Run each statement ONE AT A TIME (the console splits on ';').
--
--  Does exactly what Share's own admin "Approve" does for a yearly
--  payment (PaymentService.approveIntent): plan -> PRO_100 ("Basic"),
--  storage limit -> the plan's 100 GB, expiry -> 360 days on from today
--  (or on from the current expiry if a paid period is still running).
--  It also records the payment as APPROVED, so it shows in his payment
--  history and in yours.
--
--  The owner must have signed up at https://boxapp.magizhchi.software
--  first - P1 shows whether the account exists.
-- =====================================================================


-- P1  Before: the account and its current plan. Expect ONE row.
--     No row = he has not signed up yet. Stop here until he has.
SELECT u.id, u.email, u.display_name, u.is_verified,
       COALESCE(p.code, '(none)')                        AS plan,
       round(u.max_storage_bytes  / 1073741824.0, 1)     AS limit_gb,
       round(u.storage_used_bytes / 1073741824.0, 2)     AS used_gb,
       u.plan_expires_at
  FROM users u
  LEFT JOIN plans p ON p.id = u.plan_id
 WHERE lower(u.email) = 'marunganathan@gmail.com';


-- P2  Upgrade. One statement: it records the payment and upgrades the
--     account together. Safe to run again - the payment reference
--     MANONMANI-2026 can only be recorded once, and without a new record
--     nothing is changed (so a second run never adds another year).
--     Returns the upgraded account; no row back = already applied, or
--     no such account (see P1).
WITH pay AS (
    INSERT INTO payment_intents
           (user_id, plan_code, billing_cycle, amount_paise, reference,
            upi_vpa, payee_name, status, payment_utr, admin_note,
            created_at, updated_at, submitted_at, verified_at)
    SELECT u.id, p.code, 'yearly', COALESCE(p.yearly_paise, 0), 'MANONMANI-2026',
           'offline', 'Magizhchi Software', 'APPROVED', NULL,
           'Paid offline - Basic 100 GB yearly for Manonmani Pawn Broking, activated by hand',
           now(), now(), now(), now()
      FROM users u
      JOIN plans p ON p.code = 'PRO_100'
     WHERE lower(u.email) = 'marunganathan@gmail.com'
    ON CONFLICT (reference) DO NOTHING
    RETURNING user_id
)
UPDATE users u
   SET plan_id           = p.id,
       max_storage_bytes = p.storage_bytes,
       plan_expires_at   = GREATEST(COALESCE(u.plan_expires_at, now()), now()) + interval '360 days'
  FROM plans p, pay
 WHERE p.code = 'PRO_100'
   AND u.id   = pay.user_id
RETURNING u.id, u.email, p.label AS plan,
          round(u.max_storage_bytes / 1073741824.0, 1) AS limit_gb,
          u.plan_expires_at;


-- P3  After: expect plan PRO_100 (Basic), limit_gb 100, an expiry about a
--     year out, and one APPROVED yearly payment MANONMANI-2026.
SELECT u.email, p.code AS plan, p.label,
       round(u.max_storage_bytes / 1073741824.0, 1) AS limit_gb,
       u.plan_expires_at,
       pi.reference, pi.status, pi.billing_cycle, pi.amount_paise / 100 AS amount_rupees
  FROM users u
  LEFT JOIN plans p ON p.id = u.plan_id
  LEFT JOIN payment_intents pi ON pi.user_id = u.id AND pi.reference = 'MANONMANI-2026'
 WHERE lower(u.email) = 'marunganathan@gmail.com';


-- NEXT YEAR'S RENEWAL: run P2 again with MANONMANI-2027 in place of
-- MANONMANI-2026 (and the same in P3 to check it). It adds 360 days on
-- from whatever expiry is still running, the same as Share's own approval.
