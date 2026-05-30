-- =====================================================================
-- V4: Multi-shop access link table.
--
-- Replaces the 1-to-1 mapping in tenants.primary_email with a many-to-
-- many link table so one email can be granted access to multiple shops.
--
-- The old tenants.primary_email column is kept (for safety / external
-- tooling) but cloud-api now reads/writes user_shop_access exclusively.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.user_shop_access (
    email       TEXT NOT NULL,
    shop_id     TEXT NOT NULL REFERENCES public.tenants(shop_id),
    role        TEXT NOT NULL DEFAULT 'OWNER',  -- OWNER / VIEWER / AUDITOR
    added_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at  TIMESTAMPTZ,                    -- soft-revoke
    PRIMARY KEY (email, shop_id)
);

-- Fast email-only lookup (the OTP flow's hot path: "which shops does
-- this email have access to?").
CREATE INDEX IF NOT EXISTS ix_user_shop_access_email
    ON public.user_shop_access (lower(email))
    WHERE revoked_at IS NULL;

-- Back-fill existing tenants.primary_email rows so single-shop installs
-- (alwarpuram, annanagar, mylocal) keep working without manual SQL.
INSERT INTO public.user_shop_access (email, shop_id, role)
SELECT lower(primary_email), shop_id, 'OWNER'
  FROM public.tenants
 WHERE primary_email IS NOT NULL
   AND length(trim(primary_email)) > 0
ON CONFLICT (email, shop_id) DO NOTHING;
