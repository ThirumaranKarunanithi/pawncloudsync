-- =====================================================================
--  BILL SUSPENSE  —  company_billing_suspense
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    The Suspense feature (Today's Account -> Suspense, and the bill
--    number box on Bill Closing) reads and writes this table. A machine
--    running a build that has the feature, against a database that was
--    never given the table, fails the moment a bill number is typed:
--
--      ERROR: relation "company_billing_suspense" does not exist
--
--    The app is not at fault there and reinstalling it will not help —
--    the table has to be created once per shop.
--
--    There is a second half to the same story. A suspended bill is
--    marked by putting company_billing.status to 'SUSPENSE', and the
--    six queries behind Bill Closing all name that value. It belongs to
--    the company_bill_status enum, and an older database does not have
--    it — this script was first written from a database where it was
--    already present, so step 0 below was missing and the shop failed
--    the same way, one step further along:
--
--      ERROR: invalid input value for enum company_bill_status: "SUSPENSE"
--
--    Step 0 fixes that. Both halves are needed; either one alone still
--    breaks Bill Closing on the first bill number typed.
--
--  WHAT IT IS
--    A parked copy of a bill: every column company_billing carries, plus
--    who took it, when, why, and whether it has since been settled.
--    Generated from a database where the feature already works, so it
--    matches exactly what the app expects.
--
--  AFTER RUNNING
--    Nothing to restart. Reopen Bill Closing and type a bill number.
-- =====================================================================


-- 0. The enum value ------------------------------------------------------
--    Run this one on its own, FIRST, and not inside a BEGIN/COMMIT.
--    PostgreSQL will not let a new enum value be added and then used in
--    the same transaction, and older servers refuse it in a transaction
--    at all. psql running this file statement by statement is fine; if
--    you paste into pgAdmin, send this line by itself before the rest.
--
--    Placed after CANCELED to match repledge_bill_status, which has had
--    SUSPENSE in that position all along. IF NOT EXISTS means a shop that
--    already has the value is untouched, wherever it sits in the order.
ALTER TYPE company_bill_status ADD VALUE IF NOT EXISTS 'SUSPENSE' AFTER 'CANCELED';


-- 1. The table -----------------------------------------------------------
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

-- 2. Lookup indexes ------------------------------------------------------
--    ix_suspense_active is partial: the app's hot question is "is THIS
--    bill suspended right now?", and only SUSPENDED rows can answer it.
CREATE INDEX IF NOT EXISTS ix_suspense_lookup
    ON company_billing_suspense (company_id, jewel_material_type, bill_number, suspense_status);

CREATE INDEX IF NOT EXISTS ix_suspense_active
    ON company_billing_suspense (company_id, jewel_material_type, bill_number)
    WHERE suspense_status = 'SUSPENDED';


-- 3. Cloud sync ----------------------------------------------------------
--    Only where the sync agent has been installed — sync_capture() is
--    created by its migrations. A shop with no agent skips this and the
--    table still works locally.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'sync_capture') THEN
        DROP TRIGGER IF EXISTS trg_sync_company_billing_suspense
            ON company_billing_suspense;
        CREATE TRIGGER trg_sync_company_billing_suspense
            AFTER INSERT OR UPDATE OR DELETE ON company_billing_suspense
            FOR EACH ROW EXECUTE FUNCTION sync_capture();
        RAISE NOTICE 'sync trigger installed';
    ELSE
        RAISE NOTICE 'no sync_capture() on this machine - skipping the sync trigger';
    END IF;
END $$;


-- 4. Check ---------------------------------------------------------------
--    Both halves have to come back right. enum_has_suspense = false means
--    step 0 did not take, and Bill Closing will still fail on the first
--    bill number typed.
SELECT to_regclass('public.company_billing_suspense') AS table_now_exists,
       (SELECT count(*) FROM company_billing_suspense) AS rows_so_far,
       EXISTS (SELECT 1
                 FROM pg_enum e
                 JOIN pg_type t ON t.oid = e.enumtypid
                WHERE t.typname = 'company_bill_status'
                  AND e.enumlabel = 'SUSPENSE')        AS enum_has_suspense;
