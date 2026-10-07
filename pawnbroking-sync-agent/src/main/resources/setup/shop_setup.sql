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


-- S5d The DESKTOP APP's own tables. They are not the agent's, but they
--     belong here all the same: every one of them used to be a separate
--     script somebody had to remember to run, and a shop that missed one
--     got an error at the counter instead of a missing feature. The
--     installer does them now, so re-installing the exe is all it takes.
--
--     Silent on purpose - S8's report is the last thing this file prints.
CREATE TABLE IF NOT EXISTS company_settings (
    company_id    VARCHAR(100) NOT NULL,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, setting_key)
);

CREATE TABLE IF NOT EXISTS customer_merge_log (
    id                  BIGSERIAL PRIMARY KEY,
    merge_id            VARCHAR(40)  NOT NULL,
    merged_at           TIMESTAMP    NOT NULL DEFAULT now(),
    merged_by           VARCHAR(100),
    table_name          VARCHAR(60)  NOT NULL,
    company_id          VARCHAR(100) NOT NULL,
    jewel_material_type VARCHAR(20)  NOT NULL,
    bill_number         VARCHAR(100) NOT NULL,
    row_ref             VARCHAR(100),
    old_values          JSONB        NOT NULL,
    new_values          JSONB        NOT NULL,
    undone_at           TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_customer_merge_log_merge ON customer_merge_log (merge_id);
CREATE INDEX IF NOT EXISTS ix_customer_merge_log_open  ON customer_merge_log (merged_at) WHERE undone_at IS NULL;

CREATE TABLE IF NOT EXISTS company_receipt_print_settings (
    company_id           VARCHAR(50)  NOT NULL,
    jewel_material_type  VARCHAR(10)  NOT NULL,
    receipt              VARCHAR(40)  NOT NULL,
    printer_name         VARCHAR(200),
    print_directly       BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (company_id, jewel_material_type, receipt)
);

DO $$
DECLARE v_missing TEXT[] := ARRAY[]::TEXT[];
BEGIN
    -- The Accepted Closing term a new bill starts on, per company.
    IF to_regclass('public.company_other_settings') IS NOT NULL THEN
        ALTER TABLE company_other_settings
            ADD COLUMN IF NOT EXISTS default_closing_term VARCHAR(3);
    END IF;

    IF to_regclass('public.company_settings') IS NULL THEN
        v_missing := array_append(v_missing, 'company_settings');
    END IF;
    IF to_regclass('public.customer_merge_log') IS NULL THEN
        v_missing := array_append(v_missing, 'customer_merge_log');
    END IF;
    IF to_regclass('public.company_receipt_print_settings') IS NULL THEN
        v_missing := array_append(v_missing, 'company_receipt_print_settings');
    END IF;

    PERFORM set_config('mb.app_tables',
        CASE WHEN array_length(v_missing,1) IS NULL THEN 'ok'
             ELSE 'MISSING: ' || array_to_string(v_missing, ', ') END, false);
END $$;


-- S5e Put wrong Deficit figures right. Before the 14-09-2026 app, typing
--     the Available Balance counted each keystroke twice (774164 read as
--     7741644) and Close Account saved whatever the box showed. The right
--     Deficit is always Available - Actual. A second run finds nothing.
--
--     The new desktop app has to be installed too, or the next close
--     saves a wrong one again.
DO $$
DECLARE v_day INT := 0; v_pre INT := 0;
BEGIN
    IF to_regclass('public.company_todays_account') IS NULL THEN
        PERFORM set_config('mb.deficit', '- (no day accounts)', false);
        RETURN;
    END IF;

    UPDATE company_todays_account
       SET todays_deficit_amount = round((todays_available_amount - todays_actual_amount)::numeric, 2)
     WHERE abs(todays_deficit_amount - (todays_available_amount - todays_actual_amount)) >= 0.01;
    GET DIAGNOSTICS v_day = ROW_COUNT;

    UPDATE company_todays_account
       SET pre_deficit_amount = round((pre_available_amount - pre_actual_amount)::numeric, 2)
     WHERE abs(pre_deficit_amount - (pre_available_amount - pre_actual_amount)) >= 0.01;
    GET DIAGNOSTICS v_pre = ROW_COUNT;

    PERFORM set_config('mb.deficit',
        CASE WHEN v_day + v_pre = 0 THEN 'ok - every day already agrees'
             ELSE format('corrected %s day rows and %s carried-forward rows', v_day, v_pre) END,
        false);
END $$;


-- S5f If S5b has only just given repledge_billing its primary key, then
--     every repledge already on the cloud went up under the OLD key
--     (company_id|repledge_bill_number|bill_number - and there is no
--     bill_number column, so every leg of one repledge bill shared it and
--     the cloud kept only the last). Those rows are wrong until they are
--     sent again, and the history send below will not do it: that runs
--     once and an existing shop has long since had it.
--
--     So re-send them here, automatically, and only in that case. A no-op
--     self-UPDATE changes NO business data; it just makes the trigger
--     ship each row again under its own key. The cloud upserts, so the
--     good rows land beside the collapsed ones and the cloud's own
--     housekeeping drops the collapsed ones once the good ones exist.
DO $$
DECLARE n BIGINT;
BEGIN
    IF current_setting('mb.replpk', true) IS DISTINCT FROM 'added just now' THEN
        PERFORM set_config('mb.repledge_resent', '- (not needed)', false);
        RETURN;
    END IF;
    IF to_regclass('public.sync_outbox') IS NULL THEN
        PERFORM set_config('mb.repledge_resent',
            'WAITING - the agent has not set this database up yet. Run the setup again once it is Running.', false);
        RETURN;
    END IF;

    PERFORM set_config('app.shop_id', '${SHOP_ID}', false);
    EXECUTE 'UPDATE repledge_billing SET company_id = company_id';
    GET DIAGNOSTICS n = ROW_COUNT;
    PERFORM set_config('mb.repledge_resent',
        format('%s repledges re-sent under their own keys (the key was just added)', n), false);
END $$;


-- S5g Tamil BESIDE English, never instead of it.
--
--     Every customer in every shop is written in English letters today -
--     BALAMURUGAN, VADAKU THERU - 26,000 bills' worth across the two shop
--     databases this was checked against, and not one of them in Tamil. The
--     moment Tamil could be typed into those same columns, a shop would have
--     BALAMURUGAN on the old bills and the Tamil spelling on the new ones:
--     the same person, twice, matching neither in the customer search nor in
--     the duplicate finder, and shown as a mix on the phone.
--
--     So Tamil gets columns of its own. The English ones stay exactly as they
--     are and remain what the app searches, matches and sends to the cloud.
--     The Tamil is what the bill copy prints, when the company asks for it,
--     and where it is blank the English is printed instead - so a shop can
--     fill it in for the customers it cares about and leave the rest.
--
--     Adding these changes nothing on its own: an app that does not know
--     about them ignores them, which is what makes it safe to put one PC on
--     the new app and leave the rest of the shop alone.
DO $$
DECLARE
    v_added TEXT[] := ARRAY[]::TEXT[];
    v_tab   TEXT;
    v_col   TEXT;
    v_cols  TEXT[];
BEGIN
    FOREACH v_tab IN ARRAY ARRAY['customer_details', 'company_billing', 'company_billing_suspense'] LOOP
        IF to_regclass('public.' || v_tab) IS NULL THEN
            CONTINUE;
        END IF;
        -- The bill snapshot carries two the customer master does not.
        v_cols := CASE WHEN v_tab = 'customer_details'
                       THEN ARRAY['customer_name','spouse_name','street','area','city']
                       ELSE ARRAY['customer_name','spouse_name','street','area','city','nominee_name','items']
                  END;
        FOREACH v_col IN ARRAY v_cols LOOP
            -- Only beside a column that is really there: these tables differ a
            -- little between a 2022 shop and a new one.
            IF EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = v_tab AND column_name = v_col)
               AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = v_tab AND column_name = v_col || '_ta') THEN
                EXECUTE format('ALTER TABLE %I ADD COLUMN %I character varying(500)', v_tab, v_col || '_ta');
                v_added := array_append(v_added, v_tab || '.' || v_col || '_ta');
            END IF;
        END LOOP;
    END LOOP;

    PERFORM set_config('mb.tamil_cols',
        CASE WHEN array_length(v_added,1) IS NULL
             THEN 'ok - already there'
             ELSE format('added %s', array_length(v_added,1)) END, false);
END $$;


-- S5h The Tamil word list: each English word spelled ONCE, for the whole shop.
--
--     Counted on a real shop: 37,413 bills, but only 1,756 different
--     customer names, 647 streets, 281 areas, 69 cities and 284 jewels.
--     About 2,750 words cover every bill there is. Spelling them one bill
--     at a time would be thirteen times the work for the same answer, and
--     a name corrected on one bill would still be wrong on the other two
--     hundred it appears on.
--
--     So the Tamil for a word is kept here, once, and every bill that uses
--     that word prints it - bills from 2022 as readily as one entered
--     tomorrow. The _ta columns on a bill stay: they are the exception,
--     for the one customer who spells their own name differently.
--
--     checked = false means a machine worked it out from the sound of the
--     English and nobody has looked at it yet. It still prints; the review
--     screen lists them so a shop can correct as it goes.
--
--     Shop-wide, not per company: a street is the same street whichever
--     company the bill belongs to.
CREATE TABLE IF NOT EXISTS tamil_words (
    kind        character varying(20)  NOT NULL,     -- NAME, SPOUSE, STREET, AREA, CITY, ITEM
    english     character varying(500) NOT NULL,     -- held upper case, which is how the shop types
    tamil       character varying(500),
    checked     boolean NOT NULL DEFAULT false,
    updated_at  timestamp without time zone NOT NULL DEFAULT now(),
    updated_by  character varying(100),
    PRIMARY KEY (kind, english)
);

DO $$
BEGIN
    PERFORM set_config('mb.tamil_words',
        CASE WHEN to_regclass('public.tamil_words') IS NULL THEN 'MISSING'
             ELSE (SELECT count(*) || ' word(s)' FROM tamil_words) END, false);
END $$;


-- S5i The jewels on a bill, one line each.
--
--     A bill has always held its jewels as one piece of text - "STUD-2,
--     RING-1, CHAIN-1" - and one weight, one purity and one net for the lot.
--     That works until a customer brings in a 70% stud and a 916 ring
--     together, which is an ordinary morning in a pawn shop. The net weight
--     is not worked out the same way at both purities:
--
--         gold    purity <  88  ->  net = gross x purity%
--                 purity >= 88  ->  net = gross - (gross x the company's reduction)
--         silver  purity >  80  ->  net = gross x purity%
--                 purity <= 80  ->  net = gross - (gross x the company's reduction)
--
--     so one purity for the whole bill forces BOTH jewels down ONE of those
--     branches, and whichever is typed, one of them is valued wrongly. There
--     is no averaging round it; it needs a line per jewel.
--
--     The text column stays exactly as it is and is still what prints, what
--     the ledger searches and what goes to the cloud. These lines sit beside
--     it. An app that does not know about them ignores them, so one PC can
--     take the new app while the rest of the shop carries on.
CREATE OR REPLACE FUNCTION magizhchi_jewel_count(p_items text) RETURNS integer AS $$
    -- Counts the jewels in "STUD-2, RING-1". A piece with no number on the
    -- end counts as one: there are two such lines in 42,845 on a real shop,
    -- both typed before the count was ever asked for, and they are one jewel.
    --
    -- A jewel that is marked reads "STUD-2 (BROKEN)", so anything in brackets
    -- on the end comes off before the number is looked for - otherwise a
    -- broken stud would count as one jewel instead of two. Neither an item
    -- name nor any of 37,215 bills has a bracket in it, so nothing else is
    -- caught by this.
    SELECT COALESCE(sum(COALESCE(NULLIF(
               substring(regexp_replace(trim(p), '\s*\([^()]*\)$', '') FROM '-([0-9]+)$'), '')::int, 1)), 0)::int
    FROM regexp_split_to_table(COALESCE(p_items, ''), ',') AS p
    WHERE trim(p) <> '';
$$ LANGUAGE sql IMMUTABLE;

-- Worked out by the database, not by the app. A bill is written from bill
-- opening, from a rebill, by the sync agent and now and then by hand; a
-- trigger is the only place that catches all four, so the count can never
-- come to disagree with the jewels printed on the bill.
CREATE OR REPLACE FUNCTION magizhchi_set_jewel_count() RETURNS trigger AS $t$
BEGIN
    NEW.jewel_count := magizhchi_jewel_count(NEW.items);
    RETURN NEW;
END $t$ LANGUAGE plpgsql;

DO $$
DECLARE
    v_tab   TEXT;
    v_added INT := 0;
    v_filled INT := 0;
BEGIN
    -- The count, on the bill and on its parked copy.
    FOREACH v_tab IN ARRAY ARRAY['company_billing', 'company_billing_suspense'] LOOP
        IF to_regclass('public.' || v_tab) IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = v_tab
                              AND column_name = 'jewel_count') THEN
            EXECUTE format('ALTER TABLE %I ADD COLUMN jewel_count integer', v_tab);
            v_added := v_added + 1;
        END IF;
    END LOOP;

    FOREACH v_tab IN ARRAY ARRAY['company_billing', 'company_billing_suspense'] LOOP
        IF to_regclass('public.' || v_tab) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_jewel_count_%I ON %I', v_tab, v_tab);
            EXECUTE format('CREATE TRIGGER trg_jewel_count_%I BEFORE INSERT OR UPDATE ON %I '
                        || 'FOR EACH ROW EXECUTE FUNCTION magizhchi_set_jewel_count()', v_tab, v_tab);
        END IF;
    END LOOP;

    -- Every bill there has ever been, filled from its own jewels. Exact, not
    -- a guess - the count is read off the same text the bill prints.
    UPDATE company_billing SET jewel_count = magizhchi_jewel_count(items)
     WHERE jewel_count IS DISTINCT FROM magizhchi_jewel_count(items);
    GET DIAGNOSTICS v_filled = ROW_COUNT;

    PERFORM set_config('mb.jewel_count',
        CASE WHEN v_added = 0 AND v_filled = 0 THEN 'ok - already there'
             ELSE format('%s bill(s) counted', v_filled) END, false);
END $$;

-- The lines themselves. Keyed as the bill is keyed, with the line number on
-- the end, so the cloud keeps one row per line without being told anything.
CREATE TABLE IF NOT EXISTS company_bill_items (
    company_id          character varying(100) NOT NULL,
    jewel_material_type material_type          NOT NULL,
    bill_number         character varying(100) NOT NULL,
    line_no             integer                NOT NULL,
    jewel_item          character varying(200),
    jewel_count         integer NOT NULL DEFAULT 1,
    gross_weight        double precision,
    purity              double precision,
    net_weight          double precision,
    jewel_condition     character varying(500),   -- "BROKEN, STONE MISSING", as ticked
    note                character varying(500),
    created_date        timestamp without time zone NOT NULL DEFAULT now(),
    user_id             character varying(100),
    PRIMARY KEY (company_id, jewel_material_type, bill_number, line_no)
);

-- What a jewel can be marked as. A list the shop keeps, exactly like the
-- Jewel Item Module - so a shop that wants CLASP BROKEN adds it itself and
-- nothing has to be installed for it.
CREATE TABLE IF NOT EXISTS jewel_conditions (
    jewel_condition character varying(200) NOT NULL,
    sort_order      integer NOT NULL DEFAULT 0,
    status          character varying(20)  NOT NULL DEFAULT 'ACTIVE',
    created_date    timestamp without time zone NOT NULL DEFAULT now(),
    user_id         character varying(100),
    PRIMARY KEY (jewel_condition)
);

INSERT INTO jewel_conditions (jewel_condition, sort_order, user_id)
VALUES ('BROKEN', 10, 'SETUP'), ('DAMAGED', 20, 'SETUP'), ('BENT', 30, 'SETUP'),
       ('SCRATCHED', 40, 'SETUP'), ('DENT', 50, 'SETUP'), ('STONE MISSING', 60, 'SETUP'),
       ('SOLDERED', 70, 'SETUP'), ('COLOUR FADED', 80, 'SETUP')
ON CONFLICT (jewel_condition) DO NOTHING;

DO $$
DECLARE
    v_lines INT := 0;
    v_tab   TEXT;
BEGIN
    -- Old bills get their lines, with the COUNT filled and the weights left
    -- empty. There is no record of what each jewel on a 2019 bill weighed,
    -- and splitting the bill's gross by the number of jewels would put a
    -- figure nobody ever weighed onto a legal document. Blank is the truth.
    INSERT INTO company_bill_items (company_id, jewel_material_type, bill_number, line_no,
                                    jewel_item, jewel_count, jewel_condition, user_id)
    -- The same bracket rule as the count: "STUD-2 (BROKEN)" is a STUD, two of
    -- them, marked BROKEN - not a jewel called "STUD-2 (BROKEN".
    SELECT CB.company_id, CB.jewel_material_type, CB.bill_number, p.n,
           NULLIF(trim(regexp_replace(regexp_replace(trim(p.piece), '\s*\([^()]*\)$', ''),
                                      '-[0-9]+$', '')), ''),
           COALESCE(NULLIF(substring(regexp_replace(trim(p.piece), '\s*\([^()]*\)$', '')
                                     FROM '-([0-9]+)$'), '')::int, 1),
           NULLIF(trim(substring(trim(p.piece) FROM '\(([^()]*)\)$')), ''),
           'SETUP'
      FROM company_billing CB,
           unnest(regexp_split_to_array(CB.items, ',')) WITH ORDINALITY AS p(piece, n)
     WHERE CB.items IS NOT NULL AND trim(CB.items) <> '' AND trim(p.piece) <> ''
    ON CONFLICT (company_id, jewel_material_type, bill_number, line_no) DO NOTHING;
    GET DIAGNOSTICS v_lines = ROW_COUNT;

    -- V2 attached the capture trigger to every table that existed when the
    -- agent was installed. These two did not exist then, so they are attached
    -- here or they would never reach the cloud.
    FOREACH v_tab IN ARRAY ARRAY['company_bill_items', 'jewel_conditions'] LOOP
        IF EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'sync_capture') THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', v_tab, v_tab);
            EXECUTE format('CREATE TRIGGER trg_sync_%I AFTER INSERT OR UPDATE OR DELETE ON %I '
                        || 'FOR EACH ROW EXECUTE FUNCTION sync_capture()', v_tab, v_tab);
        END IF;
    END LOOP;

    PERFORM set_config('mb.jewel_lines',
        CASE WHEN v_lines = 0 THEN 'ok - already there'
             ELSE format('%s line(s) from the existing bills', v_lines) END, false);
END $$;

-- Off until a shop turns it on, and when it is on it binds only a bill being
-- opened or edited NOW. Never closing, never a rebill, never an advance - or
-- the day it was switched on, every bill opened before it could not be closed.
DO $$
BEGIN
    IF to_regclass('public.company_settings') IS NOT NULL AND to_regclass('public.company') IS NOT NULL THEN
        INSERT INTO company_settings (company_id, setting_key, setting_value)
        SELECT C.id, 'JEWEL_LINE_DETAILS', 'N' FROM company C
        ON CONFLICT (company_id, setting_key) DO NOTHING;
    END IF;
END $$;


-- S5j Cash drawers - which companies share one physical till.
--
--     A shop with two companies on one counter has ONE drawer between
--     them. Today it is counted twice and reconciled twice, and the day
--     will not close when one company has paid out money the other one's
--     cash was sitting in: that company's own books go negative, which is
--     a thing that cannot physically be true of a drawer.
--
--     Only the DAY ACCOUNT is ever shared. Bills, expenses, debits,
--     credits, profit and every report stay with the company they belong
--     to and are not touched by any of this.
--
--     A drawer can hold any number of companies; a company belongs to at
--     most one drawer, which is what the primary key below says. A shop
--     that never makes a drawer carries on exactly as it does now.
CREATE TABLE IF NOT EXISTS cash_drawer (
    drawer_id    character varying(100) NOT NULL,
    drawer_name  character varying(200) NOT NULL,
    status       character varying(20)  NOT NULL DEFAULT 'ACTIVE',
    created_date timestamp without time zone NOT NULL DEFAULT now(),
    user_id      character varying(100),
    PRIMARY KEY (drawer_id)
);

CREATE TABLE IF NOT EXISTS cash_drawer_company (
    -- The company is the key: one till per company, said by the table itself
    -- rather than by the screen remembering to check.
    company_id   character varying(100) NOT NULL,
    drawer_id    character varying(100) NOT NULL,
    created_date timestamp without time zone NOT NULL DEFAULT now(),
    user_id      character varying(100),
    PRIMARY KEY (company_id)
);

CREATE INDEX IF NOT EXISTS cash_drawer_company_drawer ON cash_drawer_company (drawer_id);

-- What the drawer itself came to on a day it was closed.
--
-- The companies keep their own rows in company_todays_account, each with
-- its own figures, exactly as they always have. This is the one thing
-- those rows cannot hold: the drawer was counted ONCE, so the money that
-- was missing from it is missing from the DRAWER and not from any one
-- company. Writing that shortfall against a company would be saying
-- which of them lost it, which nobody knows.
CREATE TABLE IF NOT EXISTS cash_drawer_day (
    drawer_id          character varying(100) NOT NULL,
    todays_date        date                   NOT NULL,
    combined_actual    double precision,
    combined_available double precision,       -- what was counted in the drawer
    combined_deficit   double precision,
    note               character varying(500),
    closed_date        timestamp without time zone NOT NULL DEFAULT now(),
    user_id            character varying(100),
    PRIMARY KEY (drawer_id, todays_date)
);

DO $$
DECLARE
    v_tab TEXT;
BEGIN
    -- V2 attached the capture trigger to every table that existed when the
    -- agent was installed; these did not exist then.
    FOREACH v_tab IN ARRAY ARRAY['cash_drawer', 'cash_drawer_company', 'cash_drawer_day'] LOOP
        IF EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'sync_capture') THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', v_tab, v_tab);
            EXECUTE format('CREATE TRIGGER trg_sync_%I AFTER INSERT OR UPDATE OR DELETE ON %I '
                        || 'FOR EACH ROW EXECUTE FUNCTION sync_capture()', v_tab, v_tab);
        END IF;
    END LOOP;

    PERFORM set_config('mb.cash_drawers',
        (SELECT count(*) || ' drawer(s), ' || (SELECT count(*) FROM cash_drawer_company)
                || ' company/companies mapped' FROM cash_drawer), false);
END $$;


-- S5k One bill number series across both metals, for the shops that want it.
--
--     A shop has always had two counters - gold on R8124 while silver is
--     on RS1639. Some want one running number across the counter instead,
--     so a day's bills read 8124, 8125, 8126 whatever the metal was. With
--     this on, BOTH metals take their next number from the GOLD counter
--     and both advance it.
--
--     Off by default, so every shop already running keeps the numbering
--     it has. Nothing renumbers an existing bill.
--
--     The app will not let it be turned on while silver holds a number
--     the gold series is going to reach, because the primary key of
--     company_billing is (company_id, jewel_material_type, bill_number) -
--     the metal is part of the key, so the database itself would accept
--     the same number twice, once in each metal.
DO $$
BEGIN
    IF to_regclass('public.company') IS NULL THEN
        PERFORM set_config('mb.one_series', 'no company table - skipped', false);
        RETURN;
    END IF;

    ALTER TABLE company ADD COLUMN IF NOT EXISTS shared_bill_number boolean NOT NULL DEFAULT false;

    PERFORM set_config('mb.one_series',
        (SELECT CASE WHEN count(*) FILTER (WHERE shared_bill_number) = 0
                     THEN 'ok - every company numbers gold and silver apart'
                     ELSE count(*) FILTER (WHERE shared_bill_number) || ' company/companies on one series'
                END
           FROM company), false);
END $$;


-- S5l The cash drawer that opens itself, and a note of every time it did.
--
--     The drawer is wired into the POS receipt printer and opens when the
--     printer pulses it. Most printers can be set to pulse on every job,
--     and that is how it has been working - which made the drawer's
--     timing the RECEIPT's timing. Once receipts moved to printing after
--     the bill was saved, the drawer began opening after the cash had
--     already been needed.
--
--     So the app opens it itself, at the moment somebody reaches for the
--     money. These two tables hold which printer it is wired to, how
--     hard to pulse it, when it may open - and, so the day's count means
--     something, every time it did open and who opened it.
--
--     Off until a shop sets it up. A shop with no drawer sees none of it.
DO $$
DECLARE
    v_tab  TEXT;
    v_made INT := 0;
BEGIN
    CREATE TABLE IF NOT EXISTS company_cash_drawer_hardware (
        company_id    VARCHAR(100) NOT NULL PRIMARY KEY REFERENCES company(id),
        drawer_on     BOOLEAN      NOT NULL DEFAULT false,
        printer_name  VARCHAR(255),
        drawer_pin    INTEGER      NOT NULL DEFAULT 1,     -- 1 = pin 2, 2 = pin 5
        pulse_ms      INTEGER      NOT NULL DEFAULT 100,
        moments       VARCHAR(255),                        -- DENOMINATION,DAY_COUNT,BUTTON
        created_date  TIMESTAMP    NOT NULL DEFAULT now(),
        user_id       VARCHAR(100)
    );

    CREATE TABLE IF NOT EXISTS cash_drawer_opening (
        company_id    VARCHAR(100) NOT NULL REFERENCES company(id),
        opened_at     TIMESTAMP    NOT NULL DEFAULT now(),
        user_id       VARCHAR(100),
        reason        VARCHAR(255)
    );
    CREATE INDEX IF NOT EXISTS idx_cash_drawer_opening_day
        ON cash_drawer_opening (company_id, opened_at);

    -- Both go to the cloud like every other table the shops keep.
    FOREACH v_tab IN ARRAY ARRAY['company_cash_drawer_hardware', 'cash_drawer_opening'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_trigger
                        WHERE tgname = 'trg_sync_' || v_tab AND NOT tgisinternal) THEN
            EXECUTE format('CREATE TRIGGER trg_sync_%I AFTER INSERT OR UPDATE OR DELETE ON %I '
                        || 'FOR EACH ROW EXECUTE FUNCTION sync_capture()', v_tab, v_tab);
            v_made := v_made + 1;
        END IF;
    END LOOP;

    PERFORM set_config('mb.drawer_kick',
        (SELECT CASE WHEN count(*) FILTER (WHERE drawer_on) = 0
                     THEN 'ok - no shop has turned the drawer on'
                     ELSE count(*) FILTER (WHERE drawer_on) || ' company/companies opening the drawer'
                END
           FROM company_cash_drawer_hardware), false);
END $$;


-- S5m What each person did, in the order they did it.
--
--     The owner wants to read a day the way it happened: signed in at
--     9.02, opened Gold Bill Opening at 9.03, changed the Amount from
--     50,000 to 60,000 at 9.05, saved bill R8125 at 9.07, closed the
--     screen at 9.09.
--
--     EVENTS, not keystrokes. One line when a box is left with something
--     different in it, not one per letter. A shop doing 45 bills on its
--     busiest day writes a few thousand lines that way; keeping every
--     keypress would be tens of thousands of times more, would be the
--     largest table in the system by far, and would be unreadable.
--
--     Every line carries the bill number (or the entry's id) it belonged
--     to, and WHO three ways - the sign-in name, the employee id behind
--     it and the name to read - because a user can be renamed and an
--     employee can leave, and the ids are what still make sense later.
--
--     Passwords are never written, not even wrong ones.
DO $$
DECLARE
    v_made INT := 0;
BEGIN
    CREATE TABLE IF NOT EXISTS activity_log (
        id           BIGSERIAL    PRIMARY KEY,
        company_id   VARCHAR(100),
        user_id      VARCHAR(100),
        emp_id       VARCHAR(100),
        emp_name     VARCHAR(255),
        action       VARCHAR(40),
        screen       VARCHAR(120),
        bill_number  VARCHAR(100),
        detail       VARCHAR(500),
        happened_at  TIMESTAMP    NOT NULL DEFAULT now()
    );

    -- The two ways it is ever read: a person's day, and everything about one bill.
    CREATE INDEX IF NOT EXISTS idx_activity_when
        ON activity_log (company_id, happened_at DESC);
    CREATE INDEX IF NOT EXISTS idx_activity_who
        ON activity_log (company_id, user_id, happened_at DESC);
    CREATE INDEX IF NOT EXISTS idx_activity_bill
        ON activity_log (company_id, bill_number);

    IF NOT EXISTS (SELECT 1 FROM pg_trigger
                    WHERE tgname = 'trg_sync_activity_log' AND NOT tgisinternal) THEN
        EXECUTE 'CREATE TRIGGER trg_sync_activity_log AFTER INSERT OR UPDATE OR DELETE ON activity_log '
             || 'FOR EACH ROW EXECUTE FUNCTION sync_capture()';
        v_made := 1;
    END IF;

    -- A year on the shop's own PC. The cloud keeps its own, shorter, window.
    DELETE FROM activity_log WHERE happened_at < now() - INTERVAL '1 year';

    PERFORM set_config('mb.activity',
        (SELECT CASE WHEN count(*) = 0 THEN 'ok - nothing recorded yet'
                     ELSE count(*) || ' line(s), oldest ' || to_char(min(happened_at), 'DD-MM-YYYY')
                END
           FROM activity_log), false);
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
    (9,  'Desktop app tables',     COALESCE(current_setting('mb.app_tables', true), 'ok')),
    (10, 'Tamil columns (beside the English ones)',
         COALESCE(current_setting('mb.tamil_cols', true), 'ok')),
    (11, 'Tamil word list',        COALESCE(current_setting('mb.tamil_words', true), 'ok')),
    (12, 'Day account deficits',   COALESCE(current_setting('mb.deficit', true), 'ok')),
    (13, 'Repledges re-sent',      COALESCE(current_setting('mb.repledge_resent', true), '- (not needed)')),
    (14, 'History to the cloud',   current_setting('mb.history', true)),
    (15, 'Already sent',           current_setting('mb.sent', true)),
    (16, 'Waiting to send',        current_setting('mb.pending', true)),
    (17, 'Photos uploaded so far', current_setting('mb.images', true)),
    (18, 'Backups uploaded so far', current_setting('mb.backups', true)),
    (19, 'Photo folder(s) - must exist on THIS PC',  current_setting('mb.photo_dirs', true)),
    (20, 'Backup folder(s) - must exist on THIS PC', current_setting('mb.backup_dirs', true)),
    (21, 'Desktop rows: company_billing',  current_setting('mb.rows_bills', true)),
    (22, 'Desktop rows: repledge_billing', current_setting('mb.rows_repledge', true)),
    (23, 'Desktop rows: customer_details', current_setting('mb.rows_customers', true)),
    (24, 'Jewel count on every bill',  COALESCE(current_setting('mb.jewel_count', true), 'ok')),
    (25, 'Jewels, a line each',        COALESCE(current_setting('mb.jewel_lines', true), 'ok')),
    (26, 'Cash drawers',               COALESCE(current_setting('mb.cash_drawers', true), 'ok')),
    (27, 'Bill number series',         COALESCE(current_setting('mb.one_series', true), 'ok')),
    (28, 'Cash drawer opening',        COALESCE(current_setting('mb.drawer_kick', true), 'ok')),
    (29, 'Employee activity',          COALESCE(current_setting('mb.activity', true), 'ok'))
) AS report(step, item, status)
ORDER BY step;
