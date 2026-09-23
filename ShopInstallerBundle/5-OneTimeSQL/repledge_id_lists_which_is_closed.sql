-- =====================================================================
--  A BILL HOLDING SEVERAL REPLEDGE IDs - WHICH ONE IS CLOSED?
--    'REPBILL6231,REPBILL6854,REPBILL6849'  ->  which of the three came
--    back, which is still with a financier, which points at nothing.
--
--  Run on the shop's DESKTOP PostgreSQL ('pawnbroking'), in pgAdmin.
--  READ-ONLY - nothing here changes a single row. The repair lives in
--  fix_stale_repledge_ids_on_company_billing.sql, section 2.
--
--  WHY A BILL HOLDS SEVERAL
--    Rebilled Multiple merges bills. The surviving bill keeps every
--    repledge id it came from, comma separated. Closing one repledge is
--    then supposed to remove ONE element - the old app compared the
--    whole string instead, so it removed nothing and the id stayed on
--    the bill for good.
--
--  WHAT CLOSED MEANS
--    RECEIVED (the usual) or CLOSED (a rare older label). Both mean the
--    jewels are back. GIVEN and OPENED mean the bill really is out.
-- =====================================================================


-- ── L1. EVERY ELEMENT OF EVERY LIST, IN ORDER, WITH A VERDICT ────────
--    One row per id. "position" is where it sits in the list, so a list
--    of three gives rows 1, 2, 3 and you can see which of them to drop.
SELECT r.company_id,
       r.bill_number,
       r.jewel_material_type,
       r.bill_status,
       r.current_list,
       r.position,
       r.rep_id,
       COALESCE(rb.status::text, 'NO SUCH REPLEDGE')      AS id_status,
       rb.repledge_name                                   AS financier,
       rb.closing_date,
       CASE WHEN rb.status IN ('RECEIVED','CLOSED') THEN 'CLOSED - should come off'
            WHEN rb.status IN ('GIVEN','OPENED')    THEN 'still out - keep'
            ELSE 'no repledge row - look before touching' END AS verdict
  FROM (
        SELECT cb.company_id, cb.bill_number, cb.jewel_material_type,
               cb.status           AS bill_status,
               cb.repledge_bill_id AS current_list,
               trim(t.x)           AS rep_id,
               t.position
          FROM company_billing cb
          CROSS JOIN LATERAL unnest(string_to_array(cb.repledge_bill_id, ','))
                             WITH ORDINALITY AS t(x, position)
         WHERE cb.repledge_bill_id LIKE '%,%'        -- lists only
       ) r
  LEFT JOIN repledge_billing rb
    ON rb.company_id       = r.company_id
   AND rb.repledge_bill_id = r.rep_id
 ORDER BY r.bill_number, r.position;


-- ── L2. ONE ROW PER BILL: WHAT THE LIST WOULD BECOME ─────────────────
--    "would_become" is exactly what section 2 of the repair script
--    would leave behind - a preview, nothing is written. An empty
--    would_become means every id on that bill is closed and the column
--    would go back to NULL.
SELECT cb.company_id,
       cb.bill_number,
       cb.jewel_material_type,
       cb.status AS bill_status,
       cb.repledge_bill_id AS current_list,
       array_length(string_to_array(cb.repledge_bill_id, ','), 1) AS ids,
       (SELECT string_agg(trim(e), ',')
          FROM unnest(string_to_array(cb.repledge_bill_id, ',')) AS e
          JOIN repledge_billing rb
            ON rb.company_id = cb.company_id AND rb.repledge_bill_id = trim(e)
         WHERE rb.status IN ('RECEIVED','CLOSED'))                 AS closed_ones,
       COALESCE(
         (SELECT string_agg(trim(e), ',')
            FROM unnest(string_to_array(cb.repledge_bill_id, ',')) AS e
           WHERE NOT EXISTS (SELECT 1 FROM repledge_billing rb
                              WHERE rb.company_id = cb.company_id
                                AND rb.repledge_bill_id = trim(e)
                                AND rb.status IN ('RECEIVED','CLOSED'))),
         '(the column would go back to NULL)')                     AS would_become
  FROM company_billing cb
 WHERE cb.repledge_bill_id LIKE '%,%'
 ORDER BY ids DESC, cb.bill_number;


-- ── L3. ARE THERE ANY LISTS AT ALL? ──────────────────────────────────
--    Run this first if L1 and L2 come back empty: a shop that has never
--    used Rebilled Multiple has none, and then there is nothing to find.
--    iravathanallur is like this - 1,278 bills, every one holding a
--    single id.
SELECT array_length(string_to_array(repledge_bill_id, ','), 1) AS ids_on_the_bill,
       count(*) AS bills
  FROM company_billing
 WHERE COALESCE(repledge_bill_id, '') <> ''
 GROUP BY 1
 ORDER BY 1;
