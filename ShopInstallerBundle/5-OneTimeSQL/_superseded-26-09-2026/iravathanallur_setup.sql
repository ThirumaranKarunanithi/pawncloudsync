-- =====================================================================
--  IRAVATHANALLUR - COMPLETE CLOUD + DATABASE SETUP
--  shop_id: iravathanallur          Everything is filled in. Nothing to edit.
--
--  ON THE SHOP PC   pgAdmin -> Query Tool on the "pawnbroking" database
--                   -> open this file -> press F5. That is all.
--                   Read the table it shows at the end - it says what
--                   was done and what is still waiting.
--
--                   Press F5 again whenever you like. It only does what
--                   is still left, and it never sends the history twice.
--
--  ON RAILWAY       The cloud statements are in the box just below, inside
--                   a comment so that F5 on the shop PC skips them. Copy
--                   them into Railway -> Data -> Query, one at a time.
--
--  ORDER            1. Railway box, R1 to R6         (gets the sync key)
--                   2. Install the sync agent on the shop PC
--                   3. F5 on the shop PC             (this file)
--                   4. Railway box, R7 and R8        (once the queue drains)
--
--                   Pressing F5 before the agent is ready is fine: it does
--                   the schema part and tells you it is waiting.
--
--  Replaces, for this shop: iravathanallur_cloud_provision.sql,
--  suspense_company_billing_suspense.sql, re_plus_customer_pricing.sql,
--  re_plus_customer_pricing_dated.sql, notice_one_per_bill_setting.sql,
--  and initial_full_backfill.sql (built on the balamurugan_full_backfill
--  pattern instead, which is the one that stops repledges collapsing).
--
--  Not included on purpose: ledger_day_enforcement.sql. While this shop
--  is still entering history it would refuse back-dated entries - that
--  is a separate decision.
-- =====================================================================


/* =====================================================================
   RAILWAY  -  copy each statement into Data -> Query, ONE AT A TIME.
               The console splits on ';'.  NOT the shop PC.

   Already ran iravathanallur_cloud_provision.sql on Railway? Then R1-R6
   are done. Run R6 (the check) to confirm, and never re-run R5 - it
   would mint a second key alongside the first.
   =====================================================================

-- R1  Register the tenant. schema_name + display_name are NOT NULL.
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES ('iravathanallur', 'iravathanallur', 'Iravathanallur Pawn Broking')
ON CONFLICT DO NOTHING;

-- R2  The legacy primary_email (user_shop_access is what gates login).
UPDATE public.tenants SET primary_email = 'rajeshwariiravathanallur@gmail.com'
WHERE shop_id = 'iravathanallur';

-- R3  The owner - this branch only.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('rajeshwariiravathanallur@gmail.com', 'iravathanallur', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

-- R4  The two admin addresses that hold every Rajeshwari branch.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('tirukaruna@gmail.com', 'iravathanallur', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('neelamanikandank@gmail.com', 'iravathanallur', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

-- R5  The sync key - ONCE ONLY. It goes into the shop PC's sync.properties
--     as cloud.api_key. FIRST look for one that already exists - the old
--     provision file made one too, and keys are stored in plain text, so
--     an existing key can simply be read back:
--       SELECT api_key, created_at FROM public.shop_credentials
--        WHERE shop_id = 'iravathanallur' AND revoked_at IS NULL;
--     Only if that returns nothing, run this and copy the mbk_... it returns.
INSERT INTO public.shop_credentials (api_key, shop_id, label)
VALUES ('mbk_' || replace(gen_random_uuid()::text,'-','')
              || replace(gen_random_uuid()::text,'-',''),
        'iravathanallur', 'Iravathanallur shop - sync agent')
RETURNING api_key;

--     Then, in the Railway UI (not SQL): add  iravathanallur  to the
--     TENANTS variable, comma-separated, and let it redeploy (~3 min).
--     Deploy Logs should show:  Provisioning tenant schema 'iravathanallur'

-- R6  Check. Expect 1 tenant, the owner email, 3 sign-ins, 1 api key.
SELECT 'tenant'  AS what, shop_id                          AS value FROM public.tenants          WHERE shop_id='iravathanallur'
UNION ALL
SELECT 'email',  COALESCE(primary_email,'(none)')                  FROM public.tenants          WHERE shop_id='iravathanallur'
UNION ALL
SELECT 'access', email || ' (' || role || ')'                      FROM public.user_shop_access WHERE shop_id='iravathanallur' AND revoked_at IS NULL
UNION ALL
SELECT 'api_key',label                                             FROM public.shop_credentials WHERE shop_id='iravathanallur' AND revoked_at IS NULL;

-- ----- R7 and R8: only after the shop PC's report says 0 waiting -----

-- R7  What the cloud holds. company_billing and repledge_billing should
--     match the desktop exactly. company_advance_amount and
--     company_todays_account will NOT: those tables have no primary key,
--     so the cloud keeps one row per bill / per company instead of one
--     per payment / per day (measured on this shop's restored data:
--     day accounts 532 -> 2). The same is true at every shop today. The
--     full history is kept in iravathanallur.events, so it can be
--     rebuilt once those tables get real keys - the next, fleet-wide fix.
SELECT table_name, count(*) AS cloud_rows
  FROM iravathanallur.projections
 WHERE NOT deleted
   AND table_name IN ('company_billing','repledge_billing','company_advance_amount',
                      'company_todays_account','customer_details')
 GROUP BY table_name ORDER BY table_name;

-- R8  Must be 0. A repledge saved at the counter before the shop PC's F5
--     went up under the collapsed key "CMP1" and would show on the phone
--     as one extra repledge.
SELECT count(*) AS stray_collapsed_repledge
  FROM iravathanallur.projections
 WHERE table_name = 'repledge_billing' AND row_pk = 'CMP1' AND NOT deleted;

--     If it is not 0 (the real repledges are keyed by repledge_bill_id):
-- DELETE FROM iravathanallur.projections WHERE table_name = 'repledge_billing' AND row_pk = 'CMP1';

   ===================================================================== */


-- ---------------------------------------------------------------------
--  THE SYNC AGENT  (shop PC - before or after the first F5)
--
--    1. Install it from ShopInstallerBundle\3-SyncAgent.
--    2. C:\ProgramData\PawnBroking\sync.properties must say:
--           db.url=jdbc:postgresql://localhost:5432/pawnbroking
--           shop.id=iravathanallur
--           cloud.api_key=<the mbk_ key from R5>
--           batch.size=25            <-- not 200; 200 stalls on a backlog
--    3. Get-Service pawnbroking-sync   must say Running.
--    4. Within 30 seconds it creates sync_outbox and attaches its triggers.
--       Then press F5 here.
--
--  Before the F5 that sends the history: allow at least 2 GB free on the
--  drive PostgreSQL lives on. It rewrites every row it sends, and a full
--  disk makes PostgreSQL shut itself down - the desktop app with it.
--
--  Run it in pgAdmin, and when NOBODY IS BILLING. Sending ~58,000 rows
--  takes a few minutes as one step, and until it finishes, saving a bill
--  waits and the repledge screens freeze. It is not stuck - do not cancel.
--  Tools with a statement time limit will cancel it for you: the Magizhchi
--  DB Communicator stops every statement at 30 seconds, and this one
--  needs longer. A cancelled run changes nothing; just run it again here.
-- ---------------------------------------------------------------------



-- #####################################################################
--  SHOP PC  -  everything from here down runs when you press F5.
-- #####################################################################

-- S0  Right database? Stops everything - and changes nothing - if not.
DO $$
BEGIN
    IF to_regclass('public.company_billing') IS NULL THEN
        RAISE EXCEPTION 'Wrong database: this is "%", not the shop database. In pgAdmin, open the Query Tool on the pawnbroking database and press F5 again. Nothing was changed.', current_database();
    END IF;
    IF current_setting('server_version_num')::int < 120000 THEN
        RAISE EXCEPTION 'PostgreSQL % is older than 12 and cannot add an enum value inside a batch. Run S1 on its own first, then press F5. Nothing was changed.', current_setting('server_version');
    END IF;
END $$;


-- S1  SUSPENSE as a company bill status. Without it Bill Closing fails on
--     the first bill number typed:
--       invalid input value for enum company_bill_status: "SUSPENSE"
--     The restored copy of this shop's database did not have it.
ALTER TYPE company_bill_status ADD VALUE IF NOT EXISTS 'SUSPENSE' AFTER 'CANCELED';


-- S2  The suspense table - a parked copy of a bill. Without it Bill
--     Closing fails with: relation "company_billing_suspense" does not exist
CREATE TABLE IF NOT EXISTS company_billing_suspense (
    id bigserial PRIMARY KEY,
    company_id character varying(100) NOT NULL,
    repledge_bill_id character varying(100),
    jewel_material_type material_type NOT NULL,
    bill_number character varying(100) NOT NULL,
    opening_date date,
    customer_name character varying(100),
    gender gender_type,
    spouse_type character varying(10),
    spouse_name character varying(100),
    door_number character varying(10),
    street character varying(100),
    area character varying(100),
    city character varying(100),
    mobile_number character varying(50),
    items character varying(500),
    interest_type interest_type,
    amount double precision,
    interest double precision,
    document_charge double precision,
    open_taken_amount double precision,
    togive_amount double precision,
    given_amount double precision,
    closing_date date,
    total_days_or_months character varying(200),
    close_taken_amount double precision,
    toget_amount double precision,
    got_amount double precision,
    bill_status company_bill_status,
    note character varying(200),
    created_user_id character varying(100),
    created_date timestamp without time zone,
    reduce_days_or_months integer,
    taken_days_or_months double precision,
    closed_user_id character varying(100),
    closed_date timestamp without time zone,
    gross_weight double precision,
    net_weight double precision,
    purity double precision,
    total_advance_amount_paid double precision,
    minimum_days_or_months integer,
    reduce_days_or_months_type character varying(100),
    minimum_days_or_months_type character varying(100),
    rebilled_from character varying(100),
    rebilled_to character varying(100),
    accepted_closing_date date,
    notice_charge_amount double precision,
    fine_interest_taken double precision,
    fine_charge_amount double precision,
    discount_amount double precision,
    total_other_charges double precision,
    nominee_name character varying(100),
    customer_status character varying(100),
    customer_copy character varying(100),
    id_proof_type character varying(100),
    id_proof_number character varying(100),
    remind_status character varying(100),
    card_lost_charge double precision,
    cust_copy_verifed boolean,
    comp_copy_verifed boolean,
    pack_copy_verifed boolean,
    closed_by character varying(1000),
    relation_to_closed_by character varying(1000),
    is_card_lost_bond_printed boolean,
    customer_id character varying(100),
    mobile_number_2 character varying(50),
    cust_id_proof_type character varying(100),
    cust_id_proof_number character varying(100),
    refered_by_name character varying(1000),
    refered_by_customer_id character varying(100),
    customer_occupation character varying(100),
    physical_location character varying(100),
    suspense_date date DEFAULT CURRENT_DATE NOT NULL,
    suspense_note character varying(500),
    taken_by_name character varying(200),
    relation_to_taken_by character varying(200),
    suspense_status character varying(20) DEFAULT 'SUSPENDED'::character varying NOT NULL,
    suspense_created_user_id character varying(100),
    suspense_created_date timestamp without time zone DEFAULT now(),
    settled_at timestamp without time zone,
    settled_by_user_id character varying(100),
    CONSTRAINT company_billing_suspense_suspense_status_check CHECK (((suspense_status)::text = ANY ((ARRAY['SUSPENDED'::character varying, 'SETTLED'::character varying, 'CANCELLED'::character varying])::text[])))
);

CREATE INDEX IF NOT EXISTS ix_suspense_lookup
    ON company_billing_suspense (company_id, jewel_material_type, bill_number, suspense_status);

CREATE INDEX IF NOT EXISTS ix_suspense_active
    ON company_billing_suspense (company_id, jewel_material_type, bill_number)
    WHERE suspense_status = 'SUSPENDED';


-- S3  Re+ per-customer pricing columns. MUST come before S4, which copies
--     these four columns - on a database without them S4 fails.
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS interest        NUMERIC;
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS document_charge NUMERIC;
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS open_formula    TEXT;
ALTER TABLE customer_details ADD COLUMN IF NOT EXISTS close_formula   TEXT;


-- S4  Re+ date-versioned pricing - bills keep the rate they were opened
--     under. Harmless without Re+ customers: the table simply stays empty.
CREATE TABLE IF NOT EXISTS customer_pricing (
    id              BIGSERIAL PRIMARY KEY,
    company_id      VARCHAR(50)  NOT NULL,
    customer_id     VARCHAR(50)  NOT NULL,
    interest        NUMERIC,
    document_charge NUMERIC,
    open_formula    TEXT,
    close_formula   TEXT,
    date_from       DATE NOT NULL,
    date_to         DATE NOT NULL DEFAULT DATE '2999-12-31',
    created_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_customer_pricing_period
    ON customer_pricing (company_id, customer_id, date_from);

CREATE INDEX IF NOT EXISTS ix_customer_pricing_lookup
    ON customer_pricing (company_id, customer_id, date_from, date_to);

INSERT INTO customer_pricing
       (company_id, customer_id, interest, document_charge,
        open_formula, close_formula, date_from, date_to)
SELECT cd.company_id, cd.customer_id, cd.interest, cd.document_charge,
       cd.open_formula, cd.close_formula, DATE '1900-01-01', DATE '2999-12-31'
  FROM customer_details cd
 WHERE cd.customer_id IS NOT NULL
   AND trim(cd.customer_id) <> ''
   AND (cd.interest        IS NOT NULL
     OR cd.document_charge IS NOT NULL
     OR (cd.open_formula   IS NOT NULL AND trim(cd.open_formula)  <> '')
     OR (cd.close_formula  IS NOT NULL AND trim(cd.close_formula) <> ''))
ON CONFLICT (company_id, customer_id, date_from) DO NOTHING;


-- S5  Notice mode. FALSE = one notice per customer, exactly as today.
ALTER TABLE company
    ADD COLUMN IF NOT EXISTS notice_one_per_bill BOOLEAN NOT NULL DEFAULT FALSE;


-- S6  Send the history to the cloud - when, and only when, it is right to.
--
--     Everything is inside one block so that a "not yet" never undoes
--     S1-S5: it notes why in the report and steps aside. It runs only if
--     the agent is ready, and only once - afterwards it marks sync_outbox
--     with a comment, and every later F5 sees that and does nothing.
--
--     Changes no business data. It gives repledge_billing a primary key -
--     without one, every repledge would share the key "CMP1" on the cloud
--     and the phone would show exactly ONE (measured on this shop's
--     restored data: 9,475 -> 1) - then touches each row with a no-op
--     UPDATE so the agent's trigger ships it. Rows already SENT are kept:
--     they are the local record of what reached the cloud and when, which
--     a date-tamper investigation reads. (The older backfill wiped them.)
DO $$
DECLARE
    v_capture TEXT;
    v_mark    TEXT;
    v_dupes   BIGINT;
    v_nulls   BIGINT;
    t         TEXT;
    col       TEXT;
    c         BIGINT;
    grand     BIGINT := 0;
    tables    TEXT[] := ARRAY[
        'company','company_billing','customer_details','repledge_billing',
        'company_advance_amount','company_todays_account',
        'company_todays_account_available_amount',
        'employee_daily_allowance_debit','employee_advance_amount_debit',
        'employee_salary_amount_debit','employee_other_amount_debit',
        'company_bill_debit','company_other_debit',
        'repledge_bill_debit','repledge_other_debit',
        'employee_advance_amount_credit','employee_other_amount_credit',
        'company_bill_credit','company_other_credit',
        'repledge_bill_credit','repledge_other_credit'];
BEGIN
    -- Is the agent ready?
    IF to_regclass('public.sync_outbox') IS NULL THEN
        PERFORM set_config('iv.history',
            'WAITING FOR THE SYNC AGENT - it has not set up this database yet. Check Get-Service pawnbroking-sync says Running and that db.url in sync.properties ends in /pawnbroking, wait 30 seconds, press F5 again.', false);
        RETURN;
    END IF;

    SELECT CASE WHEN prosrc LIKE '%indisprimary%' THEN 'pk-aware' ELSE 'old' END
      INTO v_capture FROM pg_proc WHERE proname = 'sync_capture';
    IF v_capture IS DISTINCT FROM 'pk-aware' THEN
        PERFORM set_config('iv.history',
            'WAITING - the sync agent on this PC is out of date and would send rows with the wrong keys. Run 3-SyncAgent\update-agent.bat as administrator, wait 30 seconds, press F5 again.', false);
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_sync_company_billing')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_sync_repledge_billing') THEN
        PERFORM set_config('iv.history',
            'WAITING - the agent is still attaching its triggers. Wait 30 seconds and press F5 again.', false);
        RETURN;
    END IF;

    -- Already sent?
    v_mark := obj_description('public.sync_outbox'::regclass, 'pg_class');
    IF v_mark LIKE 'iravathanallur history queued%' THEN
        PERFORM set_config('iv.history',
            'DONE EARLIER (' || v_mark || ') - not sent again. To send everything again on purpose, run: COMMENT ON TABLE sync_outbox IS NULL;  then press F5.', false);
        RETURN;
    END IF;

    -- repledge_billing primary key - never send collapsed repledges.
    IF NOT EXISTS (SELECT 1 FROM pg_index
                    WHERE indrelid = 'public.repledge_billing'::regclass AND indisprimary) THEN
        -- count(col) skips NULLs, so empty ids are not also counted as repeats.
        SELECT count(repledge_bill_id) - count(DISTINCT repledge_bill_id),
               count(*) FILTER (WHERE repledge_bill_id IS NULL)
          INTO v_dupes, v_nulls
          FROM repledge_billing;
        IF v_dupes > 0 OR v_nulls > 0 THEN
            PERFORM set_config('iv.history', format(
                'STOPPED - repledge_billing has %s repeated and %s empty repledge_bill_id values, so it cannot take a primary key, and sending now would collapse repledges on the cloud. Find them with: SELECT repledge_bill_id, count(*) FROM repledge_billing GROUP BY 1 HAVING count(*) > 1 OR repledge_bill_id IS NULL;  Fix them, then press F5.',
                v_dupes, v_nulls), false);
            RETURN;
        END IF;
        ALTER TABLE repledge_billing ADD PRIMARY KEY (repledge_bill_id);
    END IF;

    -- Anything still WAITING may carry keys from before the primary key.
    PERFORM set_config('app.shop_id', 'iravathanallur', false);
    DELETE FROM sync_outbox WHERE sent_at IS NULL;

    -- Every existing row of the tables the phone reads, including all 14
    -- expense/income ledgers behind Today's Account.
    FOREACH t IN ARRAY tables LOOP
        CONTINUE WHEN to_regclass('public.' || t) IS NULL;
        SELECT column_name INTO col FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = t
           AND is_identity = 'NO' AND is_generated = 'NEVER'
         ORDER BY ordinal_position LIMIT 1;
        CONTINUE WHEN col IS NULL;
        EXECUTE format('UPDATE %I SET %I = %I', t, col, col);
        GET DIAGNOSTICS c = ROW_COUNT;
        grand := grand + c;
    END LOOP;

    EXECUTE format('COMMENT ON TABLE sync_outbox IS %L',
                   format('iravathanallur history queued %s, %s rows',
                          to_char(now(), 'DD-MM-YYYY HH24:MI'), grand));
    PERFORM pg_notify('sync_channel', 'backfill');

    PERFORM set_config('iv.history', format(
        'SENT - %s rows queued for the cloud just now. Leave the PC on; the agent sends 25 at a time. Press F5 later to watch "Waiting to send" fall to 0.',
        grand), false);
END $$;


-- S7  Values for the report. Anything that might not exist yet - the
--     agent's tables, the folder columns - is read only if it does, so
--     the report can never be the thing that fails the whole F5.
DO $$
DECLARE n BIGINT; v_txt TEXT;
BEGIN
    -- Photos and backups are FILES the agent uploads by itself - only from
    -- folders that exist ON THIS PC (a database restore does not copy
    -- them), and only after someone signs in once on the phone as
    -- rajeshwariiravathanallur@gmail.com. Until then every upload gets 503.
    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_other_settings'
                  AND column_name = 'camera_temp_file_name') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT camera_temp_file_name, '   |   ')
                     FROM company_other_settings
                    WHERE camera_temp_file_name IS NOT NULL
                      AND trim(camera_temp_file_name) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('iv.photo_dirs', COALESCE(v_txt, '(none set)'), false);

    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company'
                  AND column_name = 'backup_file_path') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT backup_file_path, '   |   ')
                     FROM company
                    WHERE backup_file_path IS NOT NULL
                      AND trim(backup_file_path) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('iv.backup_dirs', COALESCE(v_txt, '(none set)'), false);

    IF to_regclass('public.sync_outbox') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_outbox WHERE sent_at IS NULL' INTO n;
        PERFORM set_config('iv.pending', n::text, false);
    ELSE
        PERFORM set_config('iv.pending', '- (no agent yet)', false);
    END IF;
    IF to_regclass('public.sync_image_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_image_uploads' INTO n;
        PERFORM set_config('iv.images', n::text, false);
    ELSE
        PERFORM set_config('iv.images', '- (no agent yet)', false);
    END IF;
    IF to_regclass('public.sync_backup_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_backup_uploads' INTO n;
        PERFORM set_config('iv.backups', n::text, false);
    ELSE
        PERFORM set_config('iv.backups', '- (no agent yet)', false);
    END IF;
END $$;


-- S8  THE REPORT - this is the table pgAdmin shows after F5.
SELECT step, item, status FROM (
    VALUES
    (1,  'Database',               current_database()::text),
    (2,  'SUSPENSE bill status',
         CASE WHEN EXISTS (SELECT 1 FROM pg_enum e JOIN pg_type ty ON ty.oid = e.enumtypid
                            WHERE ty.typname = 'company_bill_status' AND e.enumlabel = 'SUSPENSE')
              THEN 'ok' ELSE 'MISSING' END),
    (3,  'Suspense table',
         CASE WHEN to_regclass('public.company_billing_suspense') IS NOT NULL THEN 'ok' ELSE 'MISSING' END),
    (4,  'Re+ customer columns',
         CASE WHEN (SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'customer_details'
                       AND column_name IN ('interest','document_charge','open_formula','close_formula')) = 4
              THEN 'ok' ELSE 'MISSING' END),
    (5,  'Re+ dated pricing',
         CASE WHEN to_regclass('public.customer_pricing') IS NOT NULL THEN 'ok' ELSE 'MISSING' END),
    (6,  'Notice mode column',
         CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'company'
                              AND column_name = 'notice_one_per_bill')
              THEN 'ok' ELSE 'MISSING' END),
    (7,  'repledge_billing key',
         CASE WHEN EXISTS (SELECT 1 FROM pg_index
                            WHERE indrelid = 'public.repledge_billing'::regclass AND indisprimary)
              THEN 'ok' ELSE 'none yet - added when the history is sent' END),
    (8,  'History to the cloud',   current_setting('iv.history', true)),
    (9,  'Waiting to send',        current_setting('iv.pending', true)),
    (10, 'Photos uploaded so far', current_setting('iv.images', true)),
    (11, 'Backups uploaded so far', current_setting('iv.backups', true)),
    (12, 'Photo folder(s) - must exist on THIS PC',  current_setting('iv.photo_dirs', true)),
    (13, 'Backup folder(s) - must exist on THIS PC', current_setting('iv.backup_dirs', true))
) AS report(step, item, status)
ORDER BY step;
