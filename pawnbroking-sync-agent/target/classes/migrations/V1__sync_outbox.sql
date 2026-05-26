-- =====================================================================
-- pawnbroking-outbox / V1
-- Creates an outbox table + generic trigger that captures every
-- INSERT/UPDATE/DELETE on business tables as a JSONB event, then fires
-- a NOTIFY so the sync agent wakes immediately.
--
-- Idempotent: safe to run multiple times.
-- Apply on EACH local PostgreSQL (one per shop machine).
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS sync_outbox (
    event_id    UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    shop_id     TEXT        NOT NULL,
    table_name  TEXT        NOT NULL,
    op          CHAR(1)     NOT NULL CHECK (op IN ('I','U','D')),
    row_pk      TEXT,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at     TIMESTAMPTZ,
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT
);

CREATE INDEX IF NOT EXISTS ix_sync_outbox_unsent
    ON sync_outbox (created_at)
    WHERE sent_at IS NULL;

CREATE TABLE IF NOT EXISTS sync_outbox_dlq (
    LIKE sync_outbox INCLUDING ALL
);

-- ---------------------------------------------------------------------
-- Generic capture function. Reads shop_id from session GUC `app.shop_id`
-- which the desktop app sets right after acquiring a JDBC connection.
-- Falls back to 'DEFAULT' so legacy code paths still work.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_capture() RETURNS trigger AS $$
DECLARE
    v_shop_id   TEXT;
    v_payload   JSONB;
    v_row_pk    TEXT;
    v_event_id  UUID;
BEGIN
    BEGIN
        v_shop_id := current_setting('app.shop_id', true);
    EXCEPTION WHEN OTHERS THEN
        v_shop_id := NULL;
    END;
    IF v_shop_id IS NULL OR length(v_shop_id) = 0 THEN
        v_shop_id := 'DEFAULT';
    END IF;

    IF TG_OP = 'DELETE' THEN
        v_payload := to_jsonb(OLD);
    ELSE
        v_payload := to_jsonb(NEW);
    END IF;

    -- best-effort PK extraction: try common id columns
    v_row_pk := COALESCE(
        v_payload->>'id',
        v_payload->>'bill_no',
        v_payload->>'customer_id',
        v_payload->>'company_id',
        v_payload->>'pk'
    );

    v_event_id := gen_random_uuid();

    INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload)
    VALUES (v_event_id, v_shop_id, TG_TABLE_NAME, LEFT(TG_OP,1), v_row_pk, v_payload);

    PERFORM pg_notify('sync_channel', v_event_id::text);

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------
-- Attach triggers. If a table doesn't exist locally we skip it instead
-- of failing the whole script.
-- ---------------------------------------------------------------------
DO $$
DECLARE
    t TEXT;
    tables TEXT[] := ARRAY[
        'bill_opening','bill_closing','company_master','customer_master',
        'credit','debit','advance_amount','expense_income','stock_details',
        'user_master','repledge_master','company_advance_amount',
        'todays_account','rebill_mapper','notice_generation'
    ];
BEGIN
    FOREACH t IN ARRAY tables LOOP
        IF EXISTS (
            SELECT 1 FROM information_schema.tables
            WHERE table_schema = current_schema() AND table_name = t
        ) THEN
            EXECUTE format('DROP TRIGGER IF EXISTS trg_sync_%I ON %I', t, t);
            EXECUTE format(
                'CREATE TRIGGER trg_sync_%I
                 AFTER INSERT OR UPDATE OR DELETE ON %I
                 FOR EACH ROW EXECUTE FUNCTION sync_capture()',
                 t, t);
            RAISE NOTICE 'sync trigger attached to %', t;
        ELSE
            RAISE NOTICE 'skipped % (not present)', t;
        END IF;
    END LOOP;
END $$;
