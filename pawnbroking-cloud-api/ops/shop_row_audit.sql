-- =====================================================================
--  IS THIS SHOP HOLDING MORE ROWS THAN IT SHOULD?   (aiyanarpuram)
--  Railway -> Postgres -> Data -> Query. One statement at a time.
--  Every statement READS. Nothing is changed by this file.
--
--  Change aiyanarpuram to any other shop to check that one instead.
--
--  WHY A SHOP CAN HOLD TOO MANY ROWS
--
--  The cloud keeps one row per (table_name, row_pk) - so one row per
--  bill, per customer, per repledge. row_pk comes from the shop PC's
--  trigger, which builds it from the table's PRIMARY KEY.
--
--  A desktop table with NO primary key emits no row_pk, and the cloud
--  then makes one up from the event id ("evt:<uuid>"). Every later
--  change to that same logical row arrives with a DIFFERENT made-up key,
--  so it lands as ANOTHER row instead of replacing the first.
--  company_advance_amount and company_todays_account are like this on
--  every shop today - they grow one row per payment, per day, per edit.
--
--  A2 and A3 are the ones that answer "are my bills duplicated". A1
--  tells you where the bulk really is.
-- =====================================================================


-- A1  What those rows actually are. Bills are only one line of this.
--     "made_up_keys" is the count that can duplicate as described above.
SELECT table_name,
       count(*) FILTER (WHERE NOT deleted)              AS live_rows,
       count(*) FILTER (WHERE row_pk LIKE 'evt:%')      AS made_up_keys,
       count(*) FILTER (WHERE deleted)                  AS deleted_rows
  FROM aiyanarpuram.projections
 GROUP BY table_name
 ORDER BY live_rows DESC;


-- A2  THE BILL COUNT, and whether any bill is there twice.
--     bill_rows and distinct_bills must be EQUAL. bill_rows is also the
--     number to compare with the desktop: on the shop PC,
--        SELECT count(*) FROM company_billing;
SELECT count(*) AS bill_rows,
       count(DISTINCT (payload->>'company_id',
                       payload->>'jewel_material_type',
                       payload->>'bill_number')) AS distinct_bills
  FROM aiyanarpuram.projections
 WHERE table_name = 'company_billing' AND NOT deleted;


-- A3  If A2 did not match, these are the duplicates - the same bill
--     under more than one key. Look at "keys": an "evt:" one means the
--     row arrived without a proper key.
SELECT payload->>'company_id'          AS company,
       payload->>'jewel_material_type' AS material,
       payload->>'bill_number'         AS bill_no,
       count(*)                        AS copies,
       string_agg(row_pk, '  |  ' ORDER BY row_pk) AS keys
  FROM aiyanarpuram.projections
 WHERE table_name = 'company_billing' AND NOT deleted
 GROUP BY 1, 2, 3
HAVING count(*) > 1
 ORDER BY copies DESC
 LIMIT 20;


-- A4  The same question for repledges. This is the one that has bitten
--     before: without a primary key on repledge_billing, every repledge
--     shared the company id as its key and only one survived. The shop
--     PC setup adds that key now.
SELECT count(*) AS repledge_rows,
       count(DISTINCT payload->>'repledge_bill_id') AS distinct_repledges,
       count(*) FILTER (WHERE row_pk IS DISTINCT FROM payload->>'repledge_bill_id') AS wrong_key
  FROM aiyanarpuram.projections
 WHERE table_name = 'repledge_billing' AND NOT deleted;


-- A5  Customers, same shape.
SELECT count(*) AS customer_rows,
       count(DISTINCT payload->>'customer_id') AS distinct_customers
  FROM aiyanarpuram.projections
 WHERE table_name = 'customer_details' AND NOT deleted;


-- A6  Photos: 59,355 for this shop. The table's key is
--     (company, material, bill, image name), so the same file cannot be
--     counted twice - but this says how they are spread, and whether one
--     bill has an unreasonable number.
SELECT company_id, material_type,
       count(*)                       AS photos,
       count(DISTINCT bill_number)    AS bills_with_photos,
       round(count(*)::numeric / NULLIF(count(DISTINCT bill_number), 0), 1) AS per_bill,
       pg_size_pretty(sum(file_size_bytes)) AS size
  FROM aiyanarpuram.bill_images
 GROUP BY company_id, material_type
 ORDER BY photos DESC;


-- A7  The worst offenders, if per_bill above looks wrong.
SELECT bill_number, material_type, count(*) AS photos
  FROM aiyanarpuram.bill_images
 GROUP BY bill_number, material_type
HAVING count(*) > 6
 ORDER BY photos DESC
 LIMIT 20;


-- =====================================================================
--  READING THE ANSWERS
--
--  A2 equal            the bills are right; the row count is simply
--                      everything else the app keeps.
--  A2 not equal        real duplicates. Send me A3 and I will say which
--                      of the two causes it is and what to delete.
--  A1 dominated by     expected today: those tables have no primary key
--  company_todays_     on the desktop, so the cloud keeps one row per
--  account /           event rather than per day or per payment. It is
--  company_advance_    also why "Rows" is not a count of anything a
--  amount              person would recognise.
--  A4 wrong_key > 0    old repledge rows from before the key was added.
--                      The shop's cloud_verify file has the DELETE.
-- =====================================================================
