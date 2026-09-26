-- =====================================================================
--  LEDGER DAY ENFORCEMENT  —  dates may only be written on the open day
--
--  Run on EACH shop's DESKTOP PostgreSQL (the 'pawnbroking' database),
--  NOT the cloud and NOT the box. Idempotent — safe to run twice.
--
--  WHY
--    A bill's date decides which day's account it lands in. Move the
--    date afterwards and two already-printed days silently change: one
--    loses the bill, the other gains it, and nothing anywhere records
--    that it happened. Silver bill RS436 was closed on 30-09-2025,
--    printed, later moved to 21-03-2026, printed again, and moved back
--    — three different truths for one bill, found by chance a year on.
--
--    The screens already refuse this when the shop's
--    allow_to_change_*_date setting is off. But that check only greys a
--    button. It cannot see a write that arrives from pgAdmin, from a
--    script, or from a future bug in the screen itself. This puts the
--    same rule where every writer has to pass it.
--
--  THE RULE
--    The ledger's open day is the day after the last accounted day:
--
--        (SELECT todays_date FROM company_todays_account
--          WHERE company_id = ? AND ref_mark = 'L')  + 1 day
--
--    A date column may only be written when both the date being set AND
--    the date being moved off are that open day. Moving a bill off an
--    accounted day is refused just as firmly as moving one onto it —
--    that direction is what changed RS436's history.
--
--  WHEN IT DOES NOTHING
--    When the shop's allow_to_change_*_date setting is TRUE. That is
--    entry mode, for migration, and it is the one switch: turn it on and
--    dates move freely, turn it off and they are held to the open day.
--    Every shop is currently TRUE, so installing this changes no
--    behaviour today. It starts working the day you turn a flag off.
--
--  IT FAILS CLOSED
--    No ledger marker, or a setting row that does not exist, means the
--    write is REFUSED rather than waved through. That is deliberate, and
--    it has a cost: a shop with no ref_mark = 'L' row cannot write any
--    date at all once its flag is off. Section 5 checks for that. Read
--    what it prints before you turn any flag off.
--
--  TO REMOVE IT
--    DROP TRIGGER IF EXISTS trg_ledger_day_company_billing ON company_billing;
--    DROP TRIGGER IF EXISTS trg_ledger_day_repledge_billing ON repledge_billing;
--    DROP TRIGGER IF EXISTS trg_ledger_day_company_advance ON company_advance_amount;
--    DROP FUNCTION IF EXISTS magizhchi_enforce_ledger_day();
--
--  WHAT IT IS NOT
--    A superuser can disable or drop this. It closes the screen path and
--    casual pgAdmin edits; it is not proof against somebody holding the
--    postgres password. That needs the app moved off the superuser
--    account, so the login in sync.properties cannot switch off its own
--    guard. Separate job, still worth doing.
-- =====================================================================


-- 1. The guard -----------------------------------------------------------
--    One function for all three tables. Each trigger passes pairs of
--    arguments: the setting that governs a column, then the column.
--    Adding a fourth table later is a CREATE TRIGGER, not a code change.
CREATE OR REPLACE FUNCTION magizhchi_enforce_ledger_day() RETURNS trigger
LANGUAGE plpgsql
AS $fn$
DECLARE
    v_oldj      jsonb;
    v_newj      jsonb;
    v_rowj      jsonb;
    v_company   text;
    v_material  text;
    v_flag_col  text;
    v_date_col  text;
    v_allowed   boolean;
    v_last      date;
    v_open      date;
    v_old       date;
    v_new       date;
    i           int;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        v_oldj := to_jsonb(OLD);
    END IF;
    IF TG_OP <> 'DELETE' THEN
        v_newj := to_jsonb(NEW);
    END IF;
    v_rowj := COALESCE(v_newj, v_oldj);

    v_company  := v_rowj ->> 'company_id';
    v_material := v_rowj ->> 'jewel_material_type';

    -- Arguments arrive as (setting, column, setting, column, ...).
    FOR i IN 0 .. array_length(TG_ARGV, 1) - 2 BY 2 LOOP

        v_flag_col := TG_ARGV[i];
        v_date_col := TG_ARGV[i + 1];

        v_old := NULLIF(v_oldj ->> v_date_col, '')::date;
        v_new := NULLIF(v_newj ->> v_date_col, '')::date;

        -- Nothing to judge unless this write actually moves the date.
        -- An amount, a note, a customer name on an old bill all pass
        -- straight through; only the date is held.
        CONTINUE WHEN v_new IS NOT DISTINCT FROM v_old;

        SELECT (to_jsonb(s) ->> v_flag_col)::boolean
          INTO v_allowed
          FROM company_other_settings s
         WHERE s.company_id = v_company
           AND s.jewel_material_type::text = v_material;

        -- Entry mode. The shop is migrating and says so; let it work.
        CONTINUE WHEN COALESCE(v_allowed, false);

        SELECT todays_date
          INTO v_last
          FROM company_todays_account
         WHERE company_id = v_company
           AND ref_mark = 'L';

        IF v_last IS NULL THEN
            RAISE EXCEPTION USING
                ERRCODE = 'MZ001',
                MESSAGE = format(
                    '%s.%s cannot be written: this shop has no ledger day marker.',
                    TG_TABLE_NAME, v_date_col),
                DETAIL  = format('company_id = %s, material = %s', v_company, v_material),
                HINT    = 'No row in company_todays_account has ref_mark = ''L'', so there is no open day to write on. The day-end close did not finish. Complete Today''s Account, or restore the marker, before entering more bills.';
        END IF;

        v_open := v_last + 1;

        IF v_new IS NOT NULL AND v_new <> v_open THEN
            RAISE EXCEPTION USING
                ERRCODE = 'MZ001',
                MESSAGE = format(
                    '%s.%s cannot be set to %s: the ledger is open on %s.',
                    TG_TABLE_NAME, v_date_col,
                    to_char(v_new, 'DD-MM-YYYY'), to_char(v_open, 'DD-MM-YYYY')),
                DETAIL  = format('Last accounted day is %s (company %s).',
                                 to_char(v_last, 'DD-MM-YYYY'), v_company),
                HINT    = 'That day has already been accounted and printed. Close the bill on the open day, or ask the owner to turn on entry mode for this shop.';
        END IF;

        IF v_old IS NOT NULL AND v_old <> v_open THEN
            RAISE EXCEPTION USING
                ERRCODE = 'MZ001',
                MESSAGE = format(
                    '%s.%s cannot be moved off %s: that day has already been accounted.',
                    TG_TABLE_NAME, v_date_col, to_char(v_old, 'DD-MM-YYYY')),
                DETAIL  = format('Ledger is open on %s (company %s).',
                                 to_char(v_open, 'DD-MM-YYYY'), v_company),
                HINT    = 'Taking a bill out of a printed day changes that day''s totals after the fact. Ask the owner to turn on entry mode if this really has to be corrected.';
        END IF;

    END LOOP;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$fn$;


-- 2. Company bills -------------------------------------------------------
--    Two dates, two separate settings.
DROP TRIGGER IF EXISTS trg_ledger_day_company_billing ON company_billing;
CREATE TRIGGER trg_ledger_day_company_billing
    BEFORE INSERT OR UPDATE OR DELETE ON company_billing
    FOR EACH ROW EXECUTE FUNCTION magizhchi_enforce_ledger_day(
        'allow_to_change_bill_opening_date', 'opening_date',
        'allow_to_change_bill_closing_date', 'closing_date');


-- 3. Repledge bills ------------------------------------------------------
DROP TRIGGER IF EXISTS trg_ledger_day_repledge_billing ON repledge_billing;
CREATE TRIGGER trg_ledger_day_repledge_billing
    BEFORE INSERT OR UPDATE OR DELETE ON repledge_billing
    FOR EACH ROW EXECUTE FUNCTION magizhchi_enforce_ledger_day(
        'allow_to_change_repledge_bill_opening_date', 'opening_date',
        'allow_to_change_repledge_bill_closing_date', 'closing_date');


-- 4. Advance receipts ----------------------------------------------------
DROP TRIGGER IF EXISTS trg_ledger_day_company_advance ON company_advance_amount;
CREATE TRIGGER trg_ledger_day_company_advance
    BEFORE INSERT OR UPDATE OR DELETE ON company_advance_amount
    FOR EACH ROW EXECUTE FUNCTION magizhchi_enforce_ledger_day(
        'allow_to_change_advance_amount_date', 'paid_date');


-- 5. Read this before turning any flag off -------------------------------
--    open_day is what dates will be held to. A company with no marker
--    shows open_day NULL — every date write there will be refused the
--    moment its flag goes off. Fix the marker first.
SELECT c.id                                   AS company_id,
       c.name                                 AS company,
       a.todays_date                          AS last_accounted_day,
       a.todays_date + 1                      AS open_day,
       CASE WHEN a.todays_date IS NULL
            THEN 'NO MARKER - fix before turning any flag off'
            ELSE 'ok' END                     AS marker
  FROM company c
  LEFT JOIN company_todays_account a
         ON a.company_id = c.id AND a.ref_mark = 'L'
 ORDER BY c.id;

--    A setting that is NULL counts as "not allowed" and will be
--    enforced. Anything listed here should be set to a real true/false
--    rather than left empty.
SELECT company_id, jewel_material_type,
       allow_to_change_bill_opening_date            AS bill_open,
       allow_to_change_bill_closing_date            AS bill_close,
       allow_to_change_advance_amount_date          AS advance,
       allow_to_change_repledge_bill_opening_date   AS rep_open,
       allow_to_change_repledge_bill_closing_date   AS rep_close
  FROM company_other_settings
 ORDER BY company_id, jewel_material_type;

--    All three triggers should be listed.
SELECT tgrelid::regclass AS table_name, tgname AS trigger_name
  FROM pg_trigger
 WHERE NOT tgisinternal
   AND tgname LIKE 'trg_ledger_day_%'
 ORDER BY 1;
