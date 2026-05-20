-- Public schema: shared tables (auth, devices, tenant registry)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE IF NOT EXISTS public.tenants (
    shop_id         TEXT PRIMARY KEY,
    schema_name     TEXT NOT NULL UNIQUE,
    display_name    TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    active          BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS public.shop_credentials (
    api_key         TEXT PRIMARY KEY,
    shop_id         TEXT NOT NULL REFERENCES public.tenants(shop_id),
    label           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at      TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS public.app_users (
    user_id         BIGSERIAL PRIMARY KEY,
    shop_id         TEXT NOT NULL REFERENCES public.tenants(shop_id),
    username        TEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    role            TEXT NOT NULL DEFAULT 'viewer',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (shop_id, username)
);

CREATE TABLE IF NOT EXISTS public.devices (
    device_id       BIGSERIAL PRIMARY KEY,
    shop_id         TEXT NOT NULL REFERENCES public.tenants(shop_id),
    user_id         BIGINT REFERENCES public.app_users(user_id),
    fcm_token       TEXT NOT NULL,
    device_label    TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_seen_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (shop_id, fcm_token)
);

CREATE TABLE IF NOT EXISTS public.refresh_tokens (
    token           TEXT PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES public.app_users(user_id),
    shop_id         TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN NOT NULL DEFAULT FALSE
);
