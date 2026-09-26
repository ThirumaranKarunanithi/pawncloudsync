-- =====================================================================
--  REPLEDGE STILL WRONG ON THE PHONE — find out why, then repair
--  26-09-2026.  Balamurugan, Manonmani, and any shop still reporting it.
--
--  THE SYMPTOM
--    Desktop Today's Account   REPLEDGE BILL OPENING  4   Cr 1,63,000
--    Phone   Today's Account   REPLEDGE BILL OPENING  1   Cr 1,05,000
--
--  WHY IT COLLAPSES
--    The cloud stores one row per (table_name, row_pk). The capture
--    trigger builds row_pk, and for repledge_billing the LEGACY key was
--        company_id | repledge_bill_number | bill_number
--    repledge_billing has no bill_number column, so the key came out as
--        CMP1|RB100|
--    Every leg of one repledge bill therefore carried the SAME key and
--    the cloud kept only the last one. Four legs -> one row on the phone.
--
--  WHY UPDATING THE AGENT MAY NOT HAVE HELPED
--    The new capture only uses the real key when the table HAS a primary
--    key. It reads pg_index; if repledge_billing has no primary key it
--    falls straight back to the legacy key above and nothing changes.
--
--    The kit adds that primary key (step S5b) — but S5b REFUSES when
--    repledge_bill_id has repeated or empty values, and says so rather
--    than forcing it. A shop in that state has:
--        new agent  +  no primary key  =  repledges still collapsing
--    That is the most likely answer for a shop where the update was
--    installed and the phone still disagrees. PART A tells you for sure.
--
--  ORDER OF WORK — do not skip ahead:
--    PART A  shop PC, read-only  — why is it wrong here?
--    PART B  shop PC            — give repledge_billing a primary key
--    PART C  RAILWAY            — drop the collapsed rows
--    PART D  shop PC            — re-send every repledge
--    PART E  both ends          — check the numbers agree
--
--  Nothing here changes a rupee of business data. PART C deletes cloud
--  projections, which are a rebuildable copy — PART D rebuilds them.
-- =====================================================================


-- ─────────────────────────────────────────────────────────────────────
--  PART A — WHY IS IT WRONG AT THIS SHOP?      *** SHOP PC, read-only ***
--  Run all five. A1 and A2 between them give the answer.
-- ─────────────────────────────────────────────────────────────────────

-- A1. Is the PK-aware capture installed? (i.e. was the agent updated?)
SELECT CASE WHEN prosrc LIKE '%indisprimary%'
            THEN 'OK - new capture installed'
            ELSE 'OLD capture - run update-agent.bat as administrator first'
       END AS a1_capture
FROM pg_proc WHERE proname = 'sync_capture';

-- A2. Does repledge_billing have a primary key? THIS IS THE USUAL CULPRIT.
SELECT COALESCE(
         (SELECT 'OK - primary key is ' || string_agg(a.attname, ',' ORDER BY x.ord)
            FROM pg_index i
            CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS x(attnum, ord)
            JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = x.attnum
           WHERE i.indrelid = 'public.repledge_billing'::regclass
             AND i.indisprimary),
         'NO PRIMARY KEY - repledges WILL collapse on the cloud however new the agent is. Run A3, then PART B.'
       ) AS a2_primary_key;

-- A3. If A2 said NO PRIMARY KEY, these are the rows standing in the way.
--     Empty result = nothing blocking it, PART B will just work.
SELECT repledge_bill_id,
       count(*)                                   AS times_used,
       CASE WHEN repledge_bill_id IS NULL THEN 'empty id' ELSE 'repeated id' END AS problem
FROM repledge_billing
GROUP BY repledge_bill_id
HAVING count(*) > 1 OR repledge_bill_id IS NULL
ORDER BY times_used DESC, 1;

-- A4. The size of the damage: how many rows the cloud is holding vs how
--     many it should hold. "would_collapse_to" is what the phone sees.
SELECT count(*)                                                        AS desktop_rows,
       count(DISTINCT COALESCE(company_id,'') || '|'
                   || COALESCE(repledge_bill_number,'') || '|')        AS would_collapse_to,
       count(*) - count(DISTINCT COALESCE(company_id,'') || '|'
                   || COALESCE(repledge_bill_number,'') || '|')        AS rows_the_phone_is_missing
FROM repledge_billing;

-- A5. One day's figures, exactly as Today's Account computes them, so you
--     can hold this against the phone. CHANGE THE DATE.
SELECT 'OPENING' AS row_name,
       count(repledge_id)                                   AS bill_count,
       sum(amount - got_amount)                             AS debit,
       sum(amount)                                          AS credit,
       sum(COALESCE(open_taken_amount - document_charge,0)) AS intr,
       sum(COALESCE(document_charge,0))                     AS doc
FROM repledge_billing
WHERE jewel_material_type = 'GOLD'::material_type
  AND opening_date = DATE '2026-09-24'          -- <-- CHANGE
UNION ALL
SELECT 'CLOSING',
       count(repledge_id),
       sum(given_amount),
       0,
       sum(COALESCE(close_taken_amount,0)),
       0
FROM repledge_billing
WHERE jewel_material_type = 'GOLD'::material_type
  AND closing_date = DATE '2026-09-24';         -- <-- CHANGE


-- ─────────────────────────────────────────────────────────────────────
--  PART B — GIVE repledge_billing A PRIMARY KEY        *** SHOP PC ***
--  Skip if A2 already said OK. This is the same key the kit's S5b adds,
--  so a shop set up later stays consistent with this one.
--
--  If A3 listed rows, fix those first — a repeated or empty
--  repledge_bill_id cannot take a key. Do NOT invent ids to force it;
--  come back with the A3 output instead, because a repeated id usually
--  means two repledges were entered over each other and somebody has to
--  decide which is real.
-- ─────────────────────────────────────────────────────────────────────
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_index
                WHERE indrelid = 'public.repledge_billing'::regclass
                  AND indisprimary) THEN
        RAISE NOTICE 'already has a primary key - nothing to do';
    ELSE
        ALTER TABLE repledge_billing ADD PRIMARY KEY (repledge_bill_id);
        RAISE NOTICE 'primary key added on repledge_bill_id';
    END IF;
END $$;


-- ─────────────────────────────────────────────────────────────────────
--  PART C — DROP THE COLLAPSED CLOUD ROWS           *** RAILWAY ***
--  Replace <SHOP> with the schema name (balamurugan / manonmani / ...).
--
--  C1 first: it shows how many rows still carry a legacy key. A legacy
--  key contains '|'; a correct one is a plain repledge_bill_id. If C1
--  returns 0 legacy rows, this shop was already repaired and the fault
--  is elsewhere — say so rather than deleting.
-- ─────────────────────────────────────────────────────────────────────
-- C1.
-- SELECT count(*) FILTER (WHERE row_pk LIKE '%|%') AS legacy_key_rows,
--        count(*) FILTER (WHERE row_pk NOT LIKE '%|%') AS good_rows,
--        count(*) AS total
--   FROM <SHOP>.projections
--  WHERE table_name = 'repledge_billing' AND NOT deleted;

-- C2. Safe: PART D re-sends every row. Nothing on the shop PC is touched.
-- DELETE FROM <SHOP>.projections WHERE table_name = 'repledge_billing';


-- ─────────────────────────────────────────────────────────────────────
--  PART D — RE-SEND EVERY REPLEDGE                  *** SHOP PC ***
--  A no-op UPDATE fires the trigger for every row, so each is re-sent
--  with its own key. Changes NO business data.
--
--  Both statements MUST run in the SAME session — without app.shop_id
--  the events are tagged 'DEFAULT' and the cloud rejects them.
-- ─────────────────────────────────────────────────────────────────────
SET app.shop_id = 'balamurugan';        -- <-- CHANGE to this shop's id

UPDATE repledge_billing SET company_id = company_id;

NOTIFY sync_channel, 'repledge-repair';


-- ─────────────────────────────────────────────────────────────────────
--  PART E — CHECK THE TWO ENDS AGREE
-- ─────────────────────────────────────────────────────────────────────
-- E1. SHOP PC — wait for this to reach 0 before looking at the cloud:
SELECT count(*) AS still_queued FROM sync_outbox WHERE sent_at IS NULL;

-- E2. SHOP PC — the number the cloud must end up with:
SELECT count(*) AS desktop_rows FROM repledge_billing;

-- E3. RAILWAY — must now equal E2, with no legacy keys left:
-- SELECT count(*) AS cloud_rows,
--        count(*) FILTER (WHERE row_pk LIKE '%|%') AS legacy_left
--   FROM <SHOP>.projections
--  WHERE table_name = 'repledge_billing' AND NOT deleted;

-- E4. Phone: reopen Today's Account for the date used in A5. The COUNTS
--     must now match the desktop.
--
--     The MONEY columns need the cloud-api release of 26-09-2026 as well:
--     before it, the phone summed repledge_billing.interest, which is the
--     financier's RATE and not an amount (that is the "Intr: 16" against
--     the desktop's 725), booked REPLEDGE BILL OPENING with no debit at
--     all, and rebuilt the closing debit instead of using given_amount.
--     Counts come from this repair; the rupees come from that release.
-- =====================================================================
