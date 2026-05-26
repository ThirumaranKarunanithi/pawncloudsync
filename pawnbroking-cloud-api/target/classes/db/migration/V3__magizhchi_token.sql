-- Per-tenant Magizhchi Share API token (mbk_…). Used by cloud-api to push
-- bill image bytes into the tenant's Magizhchi Share drive and proxy them
-- back to the Android app. Auto-minted when the user first OTP-logs into
-- the mobile app (BoxAuthController captures the box accessToken, calls
-- POST /api/api-tokens, stores the plaintext token here).
--
-- NULLable: a fresh tenant has no token until first login; image upload
-- skips that tenant until populated.
ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS magizhchi_token            TEXT,
    ADD COLUMN IF NOT EXISTS magizhchi_conversation_id  BIGINT;
