-- =====================================================================
-- pawnbroking-outbox / V1
-- Creates an outbox table + generic trigger that captures every
-- INSERT/UPDATE/DELETE on business tables as a JSONB event, then fires
-- a NOTIFY so the sync agent wakes immediately.
--
-- Idempotent: safe to run multiple times. SchemaGuard re-runs this on
-- every service start so updates to sync_capture() take effect.
-- Trigger attachment lives in V2 — it iterates every user table.
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
--
-- row_pk is composite per table so the cloud projection upsert keeps one
-- row per natural key. Pre-2026-06-12 builds used a single-column
-- COALESCE that collapsed every bill per company into one cloud row.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_capture() RETURNS trigger AS $$
DECLARE
    v_shop_id   TEXT;
    v_payload   JSONB;
    v_row_pk    TEXT;
    v_event_id  UUID;
    v_pk_cols   TEXT[];
    v_col       TEXT;
    v_parts     TEXT[] := ARRAY[]::TEXT[];
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

    -- Prefer the table's REAL primary key. The hardcoded map below assumes
    -- column names that don't hold on every shop -- repledge_billing, for
    -- one, has no bill_number column, so its key degraded to
    -- 'CMP1|<repledge_no>|' and every leg of a repledge that covers several
    -- pawn bills collapsed into a single cloud row. Reading pg_index instead
    -- keeps one cloud row per real row, whatever the table looks like.
    SELECT array_agg(a.attname ORDER BY x.ord)
      INTO v_pk_cols
      FROM pg_index i
      CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY AS x(attnum, ord)
      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = x.attnum
     WHERE i.indrelid = (quote_ident(TG_TABLE_SCHEMA) || '.'
                      || quote_ident(TG_TABLE_NAME))::regclass
       AND i.indisprimary;

    IF v_pk_cols IS NOT NULL AND array_length(v_pk_cols, 1) > 0 THEN
        FOREACH v_col IN ARRAY v_pk_cols LOOP
            v_parts := array_append(v_parts, COALESCE(v_payload->>v_col, ''));
        END LOOP;
        v_row_pk := array_to_string(v_parts, '|');
    ELSE
    -- No primary key on this table: fall back to the known-good composites.
    v_row_pk := CASE TG_TABLE_NAME
        WHEN 'company_billing' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'jewel_material_type','') || '|' ||
            COALESCE(v_payload->>'bill_number','')
        WHEN 'customer_details' THEN
            COALESCE(v_payload->>'customer_id', v_payload->>'id', '')
        WHEN 'company_advance_amount' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'jewel_material_type','') || '|' ||
            COALESCE(v_payload->>'bill_number','') || '|' ||
            COALESCE(v_payload->>'paid_date','')
        WHEN 'company_todays_account_available_amount' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'todays_date','')
        WHEN 'company_todays_account' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'jewel_material_type','') || '|' ||
            COALESCE(v_payload->>'todays_date','') || '|' ||
            COALESCE(v_payload->>'id','')
        WHEN 'repledge_billing' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'repledge_bill_number','') || '|' ||
            COALESCE(v_payload->>'bill_number','')
        WHEN 'company_other_settings' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'jewel_material_type','')
        WHEN 'company_master' THEN
            COALESCE(v_payload->>'company_id','')
        WHEN 'company_bill_number_generator' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'jewel_material_type','')
        WHEN 'company_other_credit' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'id','')
        WHEN 'company_other_debit' THEN
            COALESCE(v_payload->>'company_id','') || '|' ||
            COALESCE(v_payload->>'id','')
        ELSE
            COALESCE(
                v_payload->>'id',
                v_payload->>'bill_number',
                v_payload->>'bill_no',
                v_payload->>'customer_id',
                v_payload->>'company_id',
                v_payload->>'pk'
            )
    END;
    END IF;

    v_event_id := gen_random_uuid();

    INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload)
    VALUES (v_event_id, v_shop_id, TG_TABLE_NAME, LEFT(TG_OP,1), v_row_pk, v_payload);

    PERFORM pg_notify('sync_channel', v_event_id::text);

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
