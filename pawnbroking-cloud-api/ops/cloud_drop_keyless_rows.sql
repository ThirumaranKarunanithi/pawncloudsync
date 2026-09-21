-- =====================================================================
--  CLEAR THE DUPLICATE LEDGER ROWS  (one shop at a time)
--  Railway -> Postgres -> Data -> Query. ONE statement at a time.
--
--  ORDER MATTERS. Do the shop PC FIRST:
--     run PawnBrokingSyncSetup.exe there (Full setup). It gives those
--     tables primary keys and sends their rows up again under real keys.
--     Wait until the shop's console row says nothing is waiting to send.
--  THEN run this, which removes the old copies that have made-up keys.
--
--  Run it the other way round and the shop simply loses those rows on
--  the cloud until the next send.
--
--  WHAT A MADE-UP KEY IS
--     The cloud keeps one row per (table, key). A desktop table with no
--     primary key sends no key, so the cloud invents "evt:<event id>" -
--     a different one every time the same row changes. They pile up
--     instead of replacing each other. Nothing reads them: the phone
--     reads the properly keyed rows.
-- =====================================================================


-- C1  How many there are, and in which tables. Change the shop name.
SELECT table_name,
       count(*) FILTER (WHERE row_pk LIKE 'evt:%')     AS made_up_keys,
       count(*) FILTER (WHERE row_pk NOT LIKE 'evt:%') AS real_keys
  FROM aiyanarpuram.projections
 WHERE NOT deleted
 GROUP BY table_name
HAVING count(*) FILTER (WHERE row_pk LIKE 'evt:%') > 0
 ORDER BY made_up_keys DESC;


-- C2  THE CHECK BEFORE DELETING. For every table below, real_keys must
--     already be there - that is the shop PC's re-send having arrived.
--     A table showing real_keys = 0 has NOT been re-sent yet; deleting
--     its rows now would empty it on the phone until it is.
SELECT table_name,
       count(*) FILTER (WHERE row_pk NOT LIKE 'evt:%') AS real_keys_arrived,
       CASE WHEN count(*) FILTER (WHERE row_pk NOT LIKE 'evt:%') = 0
            THEN 'WAIT - the shop PC has not re-sent this table yet'
            ELSE 'safe to clear' END AS verdict
  FROM aiyanarpuram.projections
 WHERE NOT deleted
   AND table_name IN ('company_advance_amount','company_todays_account',
                      'company_todays_account_available_amount',
                      'company_other_debit','company_other_credit')
 GROUP BY table_name
 ORDER BY table_name;


-- C3  Clear them, table by table. Do the one you just checked, not all
--     of them blindly. Repeat per table name.
DELETE FROM aiyanarpuram.projections
 WHERE table_name = 'company_advance_amount'
   AND row_pk LIKE 'evt:%';


-- C4  What the shop holds afterwards. Compare bills with the shop PC's
--     own count - they should match exactly.
SELECT table_name, count(*) AS rows
  FROM aiyanarpuram.projections
 WHERE NOT deleted
 GROUP BY table_name
 ORDER BY rows DESC;


-- =====================================================================
--  A NOTE ON events
--     This only touches projections - the current picture. The raw
--     events log keeps every message as it arrived, including the ones
--     that carried made-up keys, and the nightly prune trims it by age
--     as usual. Nothing here loses history.
-- =====================================================================
