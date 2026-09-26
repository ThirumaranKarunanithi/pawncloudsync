-- =====================================================================
--  AIYANARPURAM — DID A CLOSING DATE GET CHANGED AFTER THE FACT?
--  Run on Railway -> cloud service -> Data -> Query, ONE statement at a
--  time (the console splits on ';').
--
--  READ THIS FIRST. These queries read the cloud's append-only `events`
--  table, which the shop cannot reach or alter. But it only holds what
--  the sync agent shipped. Aiyanarpuram's history arrived as a BACKFILL,
--  and a backfill re-emits each bill's CURRENT state as one event — it
--  does not carry what the bill looked like before. So:
--
--     a change made AFTER the agent was installed  -> visible here
--     a change made BEFORE it                      -> not here at all
--
--  Query 1 tells you which case you are in. Everything after it is only
--  worth reading if Query 1 says the history goes back far enough.
--
--  And before anyone is accused: the migration date-fix scripts also
--  write closing_date by direct SQL, and look identical in this log
--  apart from their timestamp. Rule those out first.
-- =====================================================================


-- ── 1. How far back does the history actually go? ────────────────────
--  events_per_bill near 1.0 means backfill only — no before-and-after
--  was ever recorded, and Query 2 will find nothing however hard it looks.
SELECT count(*)                                    AS total_events,
       count(DISTINCT row_pk)                      AS distinct_bills,
       round(count(*)::numeric
             / NULLIF(count(DISTINCT row_pk),0), 2) AS events_per_bill,
       min(created_at)                             AS earliest_event,
       max(created_at)                             AS latest_event
FROM aiyanarpuram.events
WHERE table_name = 'company_billing';


-- ── 2. Every bill whose closing date has ever changed ────────────────
--  The real sweep. No bill number needed — it finds them all, with the
--  values it held and when each was recorded.
WITH e AS (
    SELECT row_pk,
           payload->>'bill_number'          AS bill_no,
           payload->>'jewel_material_type'  AS material,
           NULLIF(payload->>'closing_date','') AS closing_date,
           created_at
      FROM aiyanarpuram.events
     WHERE table_name = 'company_billing'
)
SELECT bill_no,
       material,
       count(DISTINCT closing_date)              AS distinct_values,
       array_agg(DISTINCT closing_date)          AS values_seen,
       min(created_at)                           AS first_recorded,
       max(created_at)                           AS last_changed
  FROM e
 WHERE closing_date IS NOT NULL
 GROUP BY bill_no, material
HAVING count(DISTINCT closing_date) > 1
 ORDER BY last_changed DESC;


-- ── 3. The full timeline of one bill ─────────────────────────────────
--  Put the bill number in. Every version the cloud ever received, oldest
--  first: what the closing date said, when the system recorded the close,
--  and which user id closed it.
SELECT created_at,
       op,
       payload->>'closing_date'    AS closing_date,
       payload->>'closed_date'     AS closed_date_system,
       payload->>'closed_user_id'  AS closed_by,
       payload->>'status'          AS status,
       payload->>'amount'          AS amount
  FROM aiyanarpuram.events
 WHERE table_name = 'company_billing'
   AND payload->>'bill_number' = 'PUT_BILL_NUMBER_HERE'
 ORDER BY created_at;


-- ── 4. Closing date disagreeing with when the close was recorded ─────
--  Works even with no history, because it reads the bill as it stands.
--  A bare SQL update of closing_date leaves closed_date untouched, so a
--  large gap is a marker.
--
--  EXPECT NOISE. A shop working a ledger day or two behind closes bills
--  on an earlier business date quite legitimately, and every migrated
--  bill carries a closed_date from the migration run. Read the biggest
--  gaps, and particularly any where the closing date sits BEFORE the
--  close was recorded by weeks or months.
SELECT payload->>'bill_number'         AS bill_no,
       payload->>'jewel_material_type' AS material,
       (payload->>'closing_date')::date        AS closing_date,
       (payload->>'closed_date')::timestamp    AS closed_date_system,
       payload->>'closed_user_id'      AS closed_by,
       (payload->>'closed_date')::timestamp::date
         - (payload->>'closing_date')::date    AS days_apart
  FROM aiyanarpuram.projections
 WHERE table_name = 'company_billing'
   AND NOT deleted
   AND NULLIF(payload->>'closing_date','') IS NOT NULL
   AND NULLIF(payload->>'closed_date','')  IS NOT NULL
   AND (payload->>'closed_date')::timestamp::date
       <> (payload->>'closing_date')::date
 ORDER BY abs((payload->>'closed_date')::timestamp::date
              - (payload->>'closing_date')::date) DESC
 LIMIT 100;


-- ── 5. What the bill says right now ──────────────────────────────────
SELECT payload->>'bill_number'        AS bill_no,
       payload->>'jewel_material_type' AS material,
       payload->>'opening_date'       AS opening_date,
       payload->>'closing_date'       AS closing_date,
       payload->>'closed_date'        AS closed_date_system,
       payload->>'closed_user_id'     AS closed_by,
       payload->>'status'             AS status,
       last_updated_at                AS cloud_last_updated
  FROM aiyanarpuram.projections
 WHERE table_name = 'company_billing'
   AND payload->>'bill_number' = 'PUT_BILL_NUMBER_HERE'
   AND NOT deleted;


-- =====================================================================
--  IF QUERY 1 SAYS THE HISTORY IS BACKFILL ONLY
--
--  The cloud cannot answer it, but two other records can, and neither
--  sits on the shop PC where it could be edited:
--
--    * The uploaded BACKUPS. Aiyanarpuram uploads a nightly pg_dump.
--      Take one from before the change, restore it somewhere separate,
--      and read that bill's closing_date. That is the original, dated,
--      and it settles the question on its own.
--
--    * The PRINTED bill. They printed it on 30-09-2025 — that paper
--      carries the date the system held at the moment it was printed.
--
--  On the shop PC itself, sync_outbox keeps sent rows, so it may hold
--  events older than the cloud's. It is worth reading, but it is also
--  the one record somebody with the password could have deleted from,
--  so treat a gap between it and the cloud as a question rather than
--  an answer.
-- =====================================================================
