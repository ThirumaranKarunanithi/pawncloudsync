-- =====================================================================
--  SHOP PC DATABASE SETUP  -  shop_id: ${SHOP_ID}
--
--  This is what PawnBrokingSyncSetup.exe runs on the shop PC after it
--  has installed the sync agent. A resolved copy is left next to the
--  agent as  shop_setup_${SHOP_ID}.sql  so the same thing can be run
--  from pgAdmin (Query Tool on the "pawnbroking" database, F5) if the
--  exe ever cannot be used. Nothing in it needs editing.
--
--  It does only what is still left, and it never sends the history
--  twice: the send marks sync_outbox with a comment, and every later
--  run sees that and steps aside.
--
--  The SUSPENSE bill status is added by the exe itself, before this
--  file, because ALTER TYPE ... ADD VALUE cannot run inside a batch on
--  older PostgreSQL. From pgAdmin run this first, on its own:
--      ALTER TYPE company_bill_status ADD VALUE IF NOT EXISTS 'SUSPENSE' AFTER 'CANCELED';
--
--  Covers, for this shop: suspense_company_billing_suspense.sql,
--  re_plus_customer_pricing.sql, re_plus_customer_pricing_dated.sql,
--  notice_one_per_bill_setting.sql, and the history send (built on the
--  balamurugan_full_backfill pattern, the one that stops repledges
--  collapsing).
--
--  Not included on purpose: ledger_day_enforcement.sql. It refuses
--  back-dated entries, which is a separate decision per shop.
-- =====================================================================


-- S0  Right database? Stops everything - and changes nothing - if not.
DO $$
BEGIN
    IF to_regclass('public.company_billing') IS NULL THEN
        RAISE EXCEPTION 'Wrong database: this is "%", not the shop database. db.url in sync.properties must end in /pawnbroking. Nothing was changed.', current_database();
    END IF;
END $$;


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


-- S5b The primary key on repledge_billing, on its own and before the
--     history block, so a shop that already sent its history by hand still
--     gets it. Without one, every repledge shares the company id as its key
--     on the cloud and the phone shows exactly ONE.
DO $$
DECLARE v_dupes BIGINT; v_nulls BIGINT;
BEGIN
    PERFORM set_config('mb.replpk', 'ok', false);
    IF to_regclass('public.repledge_billing') IS NULL THEN
        PERFORM set_config('mb.replpk', '(no repledge_billing table)', false);
        RETURN;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_index
                WHERE indrelid = 'public.repledge_billing'::regclass AND indisprimary) THEN
        RETURN;
    END IF;
    -- count(col) skips NULLs, so empty ids are not also counted as repeats.
    SELECT count(repledge_bill_id) - count(DISTINCT repledge_bill_id),
           count(*) FILTER (WHERE repledge_bill_id IS NULL)
      INTO v_dupes, v_nulls
      FROM repledge_billing;
    IF v_dupes > 0 OR v_nulls > 0 THEN
        PERFORM set_config('mb.replpk', format(
            'STOPPED - repledge_billing has %s repeated and %s empty repledge_bill_id values, so it cannot take a primary key. Until it does, repledges collapse to one row on the cloud. Find them with: SELECT repledge_bill_id, count(*) FROM repledge_billing GROUP BY 1 HAVING count(*) > 1 OR repledge_bill_id IS NULL;',
            v_dupes, v_nulls), false);
        RETURN;
    END IF;
    ALTER TABLE repledge_billing ADD PRIMARY KEY (repledge_bill_id);
    PERFORM set_config('mb.replpk', 'added just now', false);
END $$;


-- S5c Primary keys for the ledgers that have none.
--
--     The cloud keeps one row per (table, key), and the key comes from
--     this database's PRIMARY KEY. A table without one sends no key, and
--     the cloud then makes one up from the event id - so every later
--     change to the SAME row lands as ANOTHER row instead of replacing
--     it. Measured on a real shop: 87,858 cloud rows for a fraction of
--     that many real ones, nearly all of them these tables.
--
--     company_other_debit / _credit already carry an id, exactly like
--     their _bill_ siblings which are keyed (id, company_id); they are
--     keyed the same way here. The other three have no id at all, so
--     they get a plain surrogate one. Nothing reads these tables with
--     SELECT * (checked in both apps and every report), and every INSERT
--     names its columns, so an added column changes nothing.
--
--     A table whose existing rows cannot take the key is left alone and
--     said so in the report - a shop keeps working either way; it only
--     keeps multiplying rows on the cloud until the ids are fixed.
DO $$
DECLARE
    t       TEXT;
    v_dupes BIGINT;
    v_done  TEXT[] := ARRAY[]::TEXT[];
    v_left  TEXT[] := ARRAY[]::TEXT[];
BEGIN
    -- 1. The two that already have an id of their own.
    FOREACH t IN ARRAY ARRAY['company_other_debit','company_other_credit'] LOOP
        CONTINUE WHEN to_regclass('public.' || t) IS NULL;
        CONTINUE WHEN EXISTS (SELECT 1 FROM pg_index
                               WHERE indrelid = ('public.' || t)::regclass AND indisprimary);
        EXECUTE format(
            'SELECT count(*) - count(DISTINCT (id, company_id)) + count(*) FILTER (WHERE id IS NULL OR company_id IS NULL) FROM %I', t)
            INTO v_dupes;
        IF v_dupes > 0 THEN
            v_left := array_append(v_left, t || ' (' || v_dupes || ' repeated or empty ids)');
            CONTINUE;
        END IF;
        EXECUTE format('ALTER TABLE %I ADD PRIMARY KEY (id, company_id)', t);
        v_done := array_append(v_done, t);
    END LOOP;

    -- 2. The three with no id column at all. A surrogate is the honest
    --    answer: a natural key would have to assume a customer never pays
    --    twice for the same bill on the same day, and they do.
    FOREACH t IN ARRAY ARRAY['company_advance_amount',
                             'company_todays_account',
                             'company_todays_account_available_amount'] LOOP
        CONTINUE WHEN to_regclass('public.' || t) IS NULL;
        CONTINUE WHEN EXISTS (SELECT 1 FROM pg_index
                               WHERE indrelid = ('public.' || t)::regclass AND indisprimary);
        EXECUTE format('ALTER TABLE %I ADD COLUMN IF NOT EXISTS sync_row_id BIGSERIAL', t);
        EXECUTE format('ALTER TABLE %I ADD PRIMARY KEY (sync_row_id)', t);
        v_done := array_append(v_done, t);
    END LOOP;

    -- 3. Send those rows up again, now that they have keys the cloud can
    --    tell apart. Without this the cloud keeps the old made-up keys
    --    for everything already there, and only rows touched in future
    --    would be right. Each table is done once - the key can only be
    --    added once, so this block can only run once.
    IF array_length(v_done, 1) IS NOT NULL
       AND to_regclass('public.sync_outbox') IS NOT NULL
       AND EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_sync_company_billing') THEN
        PERFORM set_config('app.shop_id', '${SHOP_ID}', false);
        FOREACH t IN ARRAY v_done LOOP
            -- A no-op update: same value, same row, but it fires the
            -- capture trigger, which is what carries the new key up.
            EXECUTE format('UPDATE %I SET company_id = company_id', t);
        END LOOP;
    END IF;

    PERFORM set_config('mb.ledger_keys',
        CASE WHEN array_length(v_done,1) IS NULL AND array_length(v_left,1) IS NULL
             THEN 'ok - all of them already had one'
             ELSE COALESCE('added to ' || array_to_string(v_done, ', '), '')
                  || CASE WHEN array_length(v_left,1) IS NULL THEN ''
                          ELSE ' | STILL WITHOUT A KEY: ' || array_to_string(v_left, ', ')
                               || ' - these keep making duplicate rows on the cloud until the repeated ids are fixed'
                     END
        END, false);
END $$;


-- S6  Send the history to the cloud - when, and only when, it is right to.
--
--     Everything is inside one block so that a "not yet" never undoes
--     S2-S5: it notes why in the report and steps aside. It runs only if
--     the agent is ready, and only once - afterwards it marks sync_outbox
--     with a comment, and every later run sees that and does nothing.
--
--     Changes no business data. It gives repledge_billing a primary key -
--     without one, every repledge would share the company id as its key
--     on the cloud and the phone would show exactly ONE - then touches
--     each row with a no-op UPDATE so the agent's trigger ships it. Rows
--     already SENT are kept: they are the local record of what reached
--     the cloud and when, which a date-tamper investigation reads.
--
--     ${HISTORY_MODE_NOTE}
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
    -- Told to leave the history alone this run.
    IF '${HISTORY_MODE}' = 'skip' THEN
        PERFORM set_config('mb.history',
            'NOT THIS RUN - the setup was told to leave the history alone. Run the setup again without that option to send it.', false);
        RETURN;
    END IF;

    -- Is the agent ready?
    IF to_regclass('public.sync_outbox') IS NULL THEN
        PERFORM set_config('mb.history',
            'WAITING FOR THE SYNC AGENT - it has not set up this database yet. Check the pawnbroking-sync service is Running and that db.url in sync.properties ends in /pawnbroking, then run the setup again.', false);
        RETURN;
    END IF;

    SELECT CASE WHEN prosrc LIKE '%indisprimary%' THEN 'pk-aware' ELSE 'old' END
      INTO v_capture FROM pg_proc WHERE proname = 'sync_capture';
    IF v_capture IS DISTINCT FROM 'pk-aware' THEN
        PERFORM set_config('mb.history',
            'WAITING - the sync agent on this PC is out of date and would send rows with the wrong keys. Run the setup exe again (it installs the current agent), then run it once more.', false);
        RETURN;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_sync_company_billing')
       OR NOT EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_sync_repledge_billing') THEN
        PERFORM set_config('mb.history',
            'WAITING - the agent is still attaching its triggers. Wait 30 seconds and run the setup again.', false);
        RETURN;
    END IF;

    -- Already sent? The pattern is loose on purpose: the same mark is written
    -- by the kit's send_history_to_cloud.sql and by the per-shop setup files,
    -- so a shop that sent its history by hand is never asked to do it again.
    v_mark := obj_description('public.sync_outbox'::regclass, 'pg_class');
    IF v_mark LIKE '%history queued%' THEN
        PERFORM set_config('mb.history',
            'DONE EARLIER (' || v_mark || ') - not sent again.', false);
        RETURN;
    END IF;

    -- S5b adds the key; without it repledges would collapse on the cloud, so
    -- a shop that cannot take it does not send at all.
    IF current_setting('mb.replpk', true) LIKE 'STOPPED%' THEN
        PERFORM set_config('mb.history',
            'NOT SENT - ' || current_setting('mb.replpk', true) ||
            ' Fix those rows, then run the setup again.', false);
        RETURN;
    END IF;

    -- Anything still WAITING may carry keys from before the primary key.
    PERFORM set_config('app.shop_id', '${SHOP_ID}', false);
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
                   format('${SHOP_ID} history queued %s, %s rows',
                          to_char(now(), 'DD-MM-YYYY HH24:MI'), grand));
    PERFORM pg_notify('sync_channel', 'backfill');

    PERFORM set_config('mb.history', format(
        'SENT - %s rows queued for the cloud just now. Leave the PC on; the agent sends 25 at a time.',
        grand), false);
END $$;


-- S7  Values for the report. Anything that might not exist yet - the
--     agent's tables, the folder columns - is read only if it does, so
--     the report can never be the thing that fails the whole run.
DO $$
DECLARE n BIGINT; v_txt TEXT;
BEGIN
    -- Photos and backups are FILES the agent uploads by itself - only from
    -- folders that exist ON THIS PC, and only after someone signs in once
    -- on the phone for this shop. Until then every upload gets 503.
    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_other_settings'
                  AND column_name = 'camera_temp_file_name') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT camera_temp_file_name, '   |   ')
                     FROM company_other_settings
                    WHERE camera_temp_file_name IS NOT NULL
                      AND trim(camera_temp_file_name) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('mb.photo_dirs', COALESCE(v_txt, '(none set)'), false);

    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company'
                  AND column_name = 'backup_file_path') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT backup_file_path, '   |   ')
                     FROM company
                    WHERE backup_file_path IS NOT NULL
                      AND trim(backup_file_path) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('mb.backup_dirs', COALESCE(v_txt, '(none set)'), false);

    IF to_regclass('public.sync_outbox') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_outbox WHERE sent_at IS NULL' INTO n;
        PERFORM set_config('mb.pending', n::text, false);
        EXECUTE 'SELECT count(*) FROM sync_outbox WHERE sent_at IS NOT NULL' INTO n;
        PERFORM set_config('mb.sent', n::text, false);
    ELSE
        PERFORM set_config('mb.pending', '- (no agent yet)', false);
        PERFORM set_config('mb.sent',    '- (no agent yet)', false);
    END IF;
    IF to_regclass('public.sync_image_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_image_uploads' INTO n;
        PERFORM set_config('mb.images', n::text, false);
    ELSE
        PERFORM set_config('mb.images', '- (no agent yet)', false);
    END IF;
    IF to_regclass('public.sync_backup_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_backup_uploads' INTO n;
        PERFORM set_config('mb.backups', n::text, false);
    ELSE
        PERFORM set_config('mb.backups', '- (no agent yet)', false);
    END IF;

    -- What the cloud verify file should match once the queue drains.
    EXECUTE 'SELECT count(*) FROM company_billing' INTO n;
    PERFORM set_config('mb.rows_bills', n::text, false);
    IF to_regclass('public.repledge_billing') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM repledge_billing' INTO n;
        PERFORM set_config('mb.rows_repledge', n::text, false);
    ELSE
        PERFORM set_config('mb.rows_repledge', '(no table)', false);
    END IF;
    IF to_regclass('public.customer_details') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM customer_details' INTO n;
        PERFORM set_config('mb.rows_customers', n::text, false);
    ELSE
        PERFORM set_config('mb.rows_customers', '(no table)', false);
    END IF;
END $$;


-- S8  THE REPORT - the setup exe prints these lines, and pgAdmin shows
--     them as a table if this file is run there instead.
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
    (7,  'Ledger keys (stops duplicate rows on the cloud)',
         current_setting('mb.ledger_keys', true)),
    (8,  'repledge_billing key',
         CASE WHEN EXISTS (SELECT 1 FROM pg_index
                            WHERE indrelid = to_regclass('public.repledge_billing') AND indisprimary)
              THEN COALESCE(nullif(current_setting('mb.replpk', true), 'ok'), 'ok')
              ELSE COALESCE(current_setting('mb.replpk', true), 'none') END),
    (9,  'History to the cloud',   current_setting('mb.history', true)),
    (10, 'Already sent',           current_setting('mb.sent', true)),
    (11, 'Waiting to send',        current_setting('mb.pending', true)),
    (12, 'Photos uploaded so far', current_setting('mb.images', true)),
    (13, 'Backups uploaded so far', current_setting('mb.backups', true)),
    (14, 'Photo folder(s) - must exist on THIS PC',  current_setting('mb.photo_dirs', true)),
    (15, 'Backup folder(s) - must exist on THIS PC', current_setting('mb.backup_dirs', true)),
    (16, 'Desktop rows: company_billing',  current_setting('mb.rows_bills', true)),
    (17, 'Desktop rows: repledge_billing', current_setting('mb.rows_repledge', true)),
    (18, 'Desktop rows: customer_details', current_setting('mb.rows_customers', true))
) AS report(step, item, status)
ORDER BY step;
