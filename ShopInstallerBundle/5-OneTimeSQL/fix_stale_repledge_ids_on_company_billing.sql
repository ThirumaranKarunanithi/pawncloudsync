-- =====================================================================
--  STALE REPLEDGE IDs ON COMPANY_BILLING  —  report + repair
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    company_billing.repledge_bill_id means "this bill is WITH a
--    financier right now". When the repledge comes back the id should
--    be removed. It was not always removed, so bills still advertise a
--    repledge that was settled months ago — Bill Opening shows
--    "THIS BILL IS IN 'PORTCITY' IN THE NUMBER '29935'" on a bill whose
--    jewels are back in the safe.
--
--    The cause was that the column can hold a LIST. Rebilled Multiple
--    merges bills, and their repledges merge with them, so the
--    surviving bill keeps every id it came from:
--        REPBILL6231,REPBILL6854,REPBILL6849
--    The clearing query used  REPLEDGE_BILL_ID = ?  — whole-string
--    equality — which never matches a list, so closing one repledge on
--    its own removed nothing. Fixed in the app (updateCompanyBillToEmpty
--    now removes one element); this script cleans up what it left.
--
--  WHAT COUNTS AS CLOSED
--    repledge_billing.status = 'RECEIVED' — the jewels came back. Every
--    RECEIVED row carries a closing_date; GIVEN and OPENED never do.
--
--    'CLOSED' counts too. It is a rare old label — iravathanallur's
--    database has exactly one, from April 2022, and the bill that points
--    at it (C13881) was still advertising a financier three years later
--    because a test for RECEIVED alone walks straight past it. Both
--    labels mean the same thing: the jewels are back.
--
--  WHAT IS REPORTED BUT NEVER TOUCHED
--    An id that matches NO repledge row at all. Section 4 lists them.
--    They are usually very old ids whose repledge rows did not survive a
--    migration, and clearing them would destroy the only remaining trace
--    that the bill was ever repledged — so that stays a decision for a
--    person, per shop.
--
--  NOTHING IS LOST
--    The link survives the other way round: repledge_billing keeps
--    company_bill_number for every RECEIVED row, so the bill's repledge
--    history is still there after this runs. Only the stale "currently
--    with a financier" flag is removed.
-- =====================================================================


-- ── 1. REPORT — what is wrong, before changing anything ───────────────
--    Read-only. Run this on its own first if you want to look before
--    you leap; the repair below reports again afterwards.

\echo ''
\echo '=== Bills still carrying a CLOSED repledge id ==='

WITH ref AS (
    SELECT cb.company_id,
           cb.bill_number,
           cb.jewel_material_type,
           cb.status            AS bill_status,
           cb.repledge_bill_id  AS current_list,
           trim(x)              AS rep_id
      FROM company_billing cb
      CROSS JOIN LATERAL unnest(string_to_array(cb.repledge_bill_id, ',')) AS x
     WHERE COALESCE(cb.repledge_bill_id, '') <> ''
)
SELECT r.company_id,
       r.bill_number,
       r.jewel_material_type,
       r.bill_status,
       r.current_list,
       r.rep_id            AS closed_repledge_id,
       rb.repledge_name    AS financier,
       rb.closing_date     AS repledge_closed_on
  FROM ref r
  JOIN repledge_billing rb
    ON rb.company_id       = r.company_id
   AND rb.repledge_bill_id = r.rep_id
 WHERE rb.status IN ('RECEIVED','CLOSED')
 ORDER BY r.bill_status, r.bill_number, r.rep_id;


-- ── 2. REPAIR ─────────────────────────────────────────────────────────
--    Removes ONLY the ids whose repledge is RECEIVED or CLOSED. An id
--    whose repledge is still GIVEN or OPENED is left exactly where it
--    is, so a bill genuinely out with a financier keeps saying so — and
--    so is an id with no repledge row at all (section 4).
--
--    Element-wise, not blanking: a bill holding
--        REPBILL6231,REPBILL6854,REPBILL6849
--    where only the middle one is closed comes out as
--        REPBILL6231,REPBILL6849
--    NULLIF returns the column to NULL once the last id goes, which is
--    the empty state the rest of the application tests for.

BEGIN;

UPDATE company_billing cb
   SET repledge_bill_id = NULLIF(
           array_to_string(
               ARRAY(
                   SELECT e
                     FROM unnest(string_to_array(cb.repledge_bill_id, ',')) AS e
                    WHERE NOT EXISTS (
                          SELECT 1
                            FROM repledge_billing rb
                           WHERE rb.company_id       = cb.company_id
                             AND rb.repledge_bill_id = trim(e)
                             AND rb.status IN ('RECEIVED','CLOSED'))
               ), ','),
           '')
 WHERE COALESCE(cb.repledge_bill_id, '') <> ''
   AND EXISTS (
       SELECT 1
         FROM unnest(string_to_array(cb.repledge_bill_id, ',')) AS e
         JOIN repledge_billing rb
           ON rb.company_id       = cb.company_id
          AND rb.repledge_bill_id = trim(e)
        WHERE rb.status IN ('RECEIVED','CLOSED'));

COMMIT;


-- ── 3. VERIFY — must come back empty ──────────────────────────────────

\echo ''
\echo '=== Remaining stale references (expect 0) ==='

WITH ref AS (
    SELECT cb.company_id, cb.bill_number, trim(x) AS rep_id
      FROM company_billing cb
      CROSS JOIN LATERAL unnest(string_to_array(cb.repledge_bill_id, ',')) AS x
     WHERE COALESCE(cb.repledge_bill_id, '') <> ''
)
SELECT count(*) AS still_stale
  FROM ref r
  JOIN repledge_billing rb
    ON rb.company_id       = r.company_id
   AND rb.repledge_bill_id = r.rep_id
 WHERE rb.status IN ('RECEIVED','CLOSED');

\echo ''
\echo '=== Bills still shown as being with a financier (should all be genuine) ==='

WITH ref AS (
    SELECT cb.company_id, cb.bill_number, cb.status AS bill_status, trim(x) AS rep_id
      FROM company_billing cb
      CROSS JOIN LATERAL unnest(string_to_array(cb.repledge_bill_id, ',')) AS x
     WHERE COALESCE(cb.repledge_bill_id, '') <> ''
)
SELECT rb.status AS repledge_status, count(*) AS bills
  FROM ref r
  JOIN repledge_billing rb
    ON rb.company_id       = r.company_id
   AND rb.repledge_bill_id = r.rep_id
 GROUP BY rb.status
 ORDER BY 2 DESC;


-- ── 4. IDs POINTING AT NOTHING — reported, never changed ──────────────
--    An id that matches no repledge_billing row at all. Nothing above
--    touches these: the repair only removes ids it can prove are
--    closed, and an id it cannot find is not proof of anything.
--
--    Usually very old ids (iravathanallur has REPBILL22 and REPBILL109,
--    both on bills that are themselves CLOSED) whose repledge rows did
--    not survive some past migration. On a closed bill the stale flag
--    is harmless — nobody reads the financier line of a closed bill.
--    On an OPENED bill it is worth chasing: either the repledge row
--    should be there, or the bill is wrongly flagged.
--
--    To clear one after deciding, name the bill rather than the lot:
--      UPDATE company_billing SET repledge_bill_id = NULL
--       WHERE company_id = 'CMP1' AND bill_number = 'C4508'
--         AND jewel_material_type = 'GOLD';

\echo ''
\echo '=== Repledge ids with no repledge row (look, do not clear blindly) ==='

WITH ref AS (
    SELECT cb.company_id, cb.bill_number, cb.jewel_material_type,
           cb.status AS bill_status, cb.repledge_bill_id AS current_list,
           trim(x) AS rep_id
      FROM company_billing cb
      CROSS JOIN LATERAL unnest(string_to_array(cb.repledge_bill_id, ',')) AS x
     WHERE COALESCE(cb.repledge_bill_id, '') <> ''
)
SELECT r.company_id, r.bill_number, r.jewel_material_type,
       r.bill_status, r.current_list, r.rep_id AS id_pointing_at_nothing
  FROM ref r
  LEFT JOIN repledge_billing rb
    ON rb.company_id       = r.company_id
   AND rb.repledge_bill_id = r.rep_id
 WHERE rb.repledge_bill_id IS NULL
 ORDER BY r.bill_status, r.bill_number;
