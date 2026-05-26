-- =====================================================================
-- V3: Replace sync_capture() so row_pk is the FULL composite primary
-- key, not just a guessed first column. The old version COALESCEd
-- (id, bill_no, customer_id, company_id, pk) which collapsed every
-- company_billing row into row_pk='CMP1' — only the last bill survived
-- the ON CONFLICT (table_name, row_pk) DO UPDATE in cloud.projections.
--
-- New logic: introspect pg_index to find the table's primary key columns
-- and join their payload values with '|'. Falls back to the legacy guess
-- only when a table truly has no PK.
--
-- Safe to re-run.
-- =====================================================================

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

    -- 1. Try to resolve the real primary key columns via pg_index.
    SELECT array_agg(a.attname ORDER BY array_position(i.indkey::int[]::int[], a.attnum))
      INTO v_pk_cols
      FROM pg_index i
      JOIN pg_attribute a
        ON a.attrelid = i.indrelid
       AND a.attnum   = ANY(i.indkey)
     WHERE i.indrelid    = (TG_TABLE_SCHEMA || '.' || TG_TABLE_NAME)::regclass
       AND i.indisprimary;

    IF v_pk_cols IS NOT NULL AND array_length(v_pk_cols, 1) > 0 THEN
        FOREACH v_col IN ARRAY v_pk_cols LOOP
            v_parts := array_append(v_parts, COALESCE(v_payload->>v_col, ''));
        END LOOP;
        v_row_pk := array_to_string(v_parts, '|');
    ELSE
        -- 2. Tables with no declared PK — best-effort legacy guess.
        v_row_pk := COALESCE(
            v_payload->>'id',
            v_payload->>'bill_no',
            v_payload->>'bill_number',
            v_payload->>'customer_id',
            v_payload->>'company_id',
            v_payload->>'pk'
        );
    END IF;

    v_event_id := gen_random_uuid();

    INSERT INTO sync_outbox(event_id, shop_id, table_name, op, row_pk, payload)
    VALUES (v_event_id, v_shop_id, TG_TABLE_NAME, LEFT(TG_OP,1), v_row_pk, v_payload);

    PERFORM pg_notify('sync_channel', v_event_id::text);

    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;
