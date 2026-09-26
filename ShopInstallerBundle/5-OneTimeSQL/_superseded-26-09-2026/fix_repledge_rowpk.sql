-- =====================================================================
--  FIX: repledge (and any other) rows collapsing on the cloud
--
--  SYMPTOM
--    Today's Account on the phone shows FEWER repledge bills than the
--    desktop:  desktop "REPLEDGE BILL OPENING 4"  ->  mobile shows 1.
--
--  CAUSE
--    The capture trigger built row_pk from a hardcoded column list:
--        company_id | repledge_bill_number | bill_number
--    repledge_billing has NO bill_number column, so the key became
--        CMP1|RB100|
--    and every leg of a repledge covering several pawn bills landed on
--    the SAME key. The cloud upserts on (table_name, row_pk), so only
--    the last leg survived.
--
--  FIX (already in the new agent jar)
--    sync_capture() now reads the table's REAL primary key from
--    pg_index (repledge_billing -> sync_row_id, unique per row) and only
--    falls back to the hardcoded list when a table has no primary key.
--
--  ORDER OF WORK — follow exactly:
--    1. Update the agent jar + restart it   (STEP 0 below)
--    2. Check the blast radius              (STEP 1, shop PC)
--    3. Delete the stale cloud rows         (STEP 2, RAILWAY)
--    4. Re-emit from the desktop            (STEP 3, shop PC)
--    5. Verify                              (STEP 4)
-- =====================================================================


-- ─────────────────────────────────────────────────────────────────────
--  STEP 0 — update the agent FIRST (nothing below works without it)
-- ─────────────────────────────────────────────────────────────────────
--    Right-click update-agent.bat -> Run as administrator.
--    On start it re-applies the new sync_capture(). Confirm in the log:
--        findstr /C:"applying V1" pawnbroking-sync.out.log
--
--    Sanity-check the function actually changed (run on the SHOP PC):
SELECT CASE WHEN prosrc LIKE '%indisprimary%'
            THEN 'OK - new PK-aware capture is installed'
            ELSE 'OLD capture still installed - update the jar and restart'
       END AS capture_status
FROM pg_proc WHERE proname = 'sync_capture';


-- ─────────────────────────────────────────────────────────────────────
--  STEP 1 — BLAST RADIUS  (SHOP PC)
--  Which tables have a primary key, and what is it? Any table whose PK
--  differs from what the old hardcoded list used will get NEW row_pks,
--  so its old cloud rows must be cleared (STEP 2) to avoid duplicates.
--
--  Read it like this:
--    company_billing  pk = company_id,jewel_material_type,bill_number
--        -> same as the old key, nothing to clean up.
--    repledge_billing pk = sync_row_id
--        -> DIFFERENT from the old key, must be cleaned + re-emitted.
--    (no row for a table)  -> no PK, still uses the fallback, unchanged.
-- ─────────────────────────────────────────────────────────────────────
SELECT c.relname                                        AS table_name,
       string_agg(a.attname, ',' ORDER BY x.ord)        AS primary_key
FROM pg_index i
JOIN pg_class c ON c.oid = i.indrelid
CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS x(attnum, ord)
JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = x.attnum
WHERE i.indisprimary
  AND c.relnamespace = 'public'::regnamespace
  AND c.relname NOT LIKE 'sync_%'
GROUP BY c.relname
ORDER BY 1;


-- ─────────────────────────────────────────────────────────────────────
--  STEP 2 — CLEAR THE STALE CLOUD ROWS   *** RUN ON RAILWAY ***
--  Replace <SHOP> with the schema name (alwarpuram / annanagar /
--  balamurugan / karumbalai).
--
--  Safe: projections are a REBUILDABLE copy of the desktop data. STEP 3
--  re-sends every row. Nothing on the shop PC is touched.
--
--  Do this for repledge_billing, plus any other table STEP 1 showed with
--  a surrogate PK (sync_row_id / id) that the old key did not use.
-- ─────────────────────────────────────────────────────────────────────
-- DELETE FROM <SHOP>.projections WHERE table_name = 'repledge_billing';

--  Count before/after so you can see it worked:
-- SELECT count(*) FROM <SHOP>.projections WHERE table_name='repledge_billing' AND NOT deleted;


-- ─────────────────────────────────────────────────────────────────────
--  STEP 3 — RE-EMIT FROM THE DESKTOP  (SHOP PC, pgAdmin)
--  A no-op UPDATE fires the (new) trigger for every row, so each one is
--  re-sent with a correct, unique row_pk. Changes NO business data.
--
--  Set the shop id first or the events are tagged 'DEFAULT' and the
--  cloud rejects them. Run BOTH statements in the SAME session.
-- ─────────────────────────────────────────────────────────────────────
SET app.shop_id = 'alwarpuram';     -- <-- CHANGE to this shop's id

UPDATE repledge_billing SET company_id = company_id;

--  If STEP 1 showed other tables with surrogate PKs, re-emit them too, e.g.
-- UPDATE company_advance_amount SET company_id = company_id;

NOTIFY sync_channel, 'repledge-repair';


-- ─────────────────────────────────────────────────────────────────────
--  STEP 4 — VERIFY
-- ─────────────────────────────────────────────────────────────────────
--  4a. SHOP PC — wait for the queue to drain to 0:
SELECT count(*) AS pending FROM sync_outbox WHERE sent_at IS NULL;

--  4b. SHOP PC — the number the cloud should end up with:
SELECT count(*) AS desktop_rows FROM repledge_billing;

--  4c. RAILWAY — must now MATCH 4b:
-- SELECT count(*) FROM <SHOP>.projections
--  WHERE table_name='repledge_billing' AND NOT deleted;

--  4d. Phone: reopen Today's Account. REPLEDGE BILL OPENING / CLOSING
--      counts should now match the desktop screen exactly.


-- =====================================================================
--  NOTE ON THE OTHER MISMATCH
--    "GOLD BILL ADVANCE AMOUNT" showed 2 on the desktop but 0 on the
--    phone. That is a DIFFERENT problem (zero rows, not fewer), so this
--    script will not fix it on its own. After running the steps above,
--    check whether the advance rows reached the cloud at all:
--
--      -- SHOP PC
--      SELECT count(*) FROM company_advance_amount
--       WHERE paid_date::text LIKE '2026-08-03%';
--      -- RAILWAY
--      SELECT count(*) FROM <SHOP>.projections
--       WHERE table_name='company_advance_amount' AND NOT deleted
--         AND payload->>'paid_date' LIKE '2026-08-03%';
--
--    If the desktop has rows and the cloud has none, re-emit that table
--    too (STEP 3 pattern) and report back.
-- =====================================================================
