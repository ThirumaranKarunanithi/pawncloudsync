-- =====================================================================
-- Per-tenant schema template. Applied by TenantBootstrap into each
-- shop's schema (alwarpuram, annanagar, ...). Idempotent (IF NOT EXISTS).
-- =====================================================================

-- Raw event log: every sync POST lands here, deduped by event_id.
CREATE TABLE IF NOT EXISTS events (
    event_id        UUID PRIMARY KEY,
    table_name      TEXT NOT NULL,
    op              CHAR(1) NOT NULL,
    row_pk          TEXT,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_events_created  ON events (created_at DESC);
CREATE INDEX IF NOT EXISTS ix_events_table    ON events (table_name, created_at DESC);

-- Projection tables. Generic JSONB-backed rows keyed by (table_name, row_pk).
-- This lets us add new local tables without changing cloud schema.
CREATE TABLE IF NOT EXISTS projections (
    table_name      TEXT NOT NULL,
    row_pk          TEXT NOT NULL,
    payload         JSONB NOT NULL,
    last_op         CHAR(1) NOT NULL,
    last_event_id   UUID NOT NULL,
    last_updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (table_name, row_pk)
);
CREATE INDEX IF NOT EXISTS ix_proj_updated ON projections (last_updated_at DESC);
CREATE INDEX IF NOT EXISTS ix_proj_payload_gin ON projections USING gin (payload jsonb_path_ops);

-- Bill images: maps each (company, material, bill_number, image_name) to
-- the Magizhchi Share file id that holds the bytes. Cloud-api streams the
-- file from the box on demand using the tenant's stored mbk_ token.
CREATE TABLE IF NOT EXISTS bill_images (
    company_id      TEXT NOT NULL,
    material_type   TEXT NOT NULL,
    bill_number     TEXT NOT NULL,
    image_name      TEXT NOT NULL,
    magizhchi_file_id BIGINT NOT NULL,
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    file_size_bytes BIGINT,
    PRIMARY KEY (company_id, material_type, bill_number, image_name)
);
CREATE INDEX IF NOT EXISTS ix_bill_images_bill
    ON bill_images (company_id, material_type, bill_number);

-- Notification inbox per device (mobile reads recent items here).
CREATE TABLE IF NOT EXISTS notifications (
    notif_id        BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL,
    title           TEXT NOT NULL,
    body            TEXT NOT NULL,
    table_name      TEXT NOT NULL,
    row_pk          TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS ix_notif_created ON notifications (created_at DESC);
