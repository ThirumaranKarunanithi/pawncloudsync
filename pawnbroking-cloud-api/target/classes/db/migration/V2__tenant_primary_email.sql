-- Per-tenant designated login email. The Android app for shop X can only
-- be signed into by the email recorded here; the same email is also the
-- one that owns the shop's Magizhchi Share drive (used later for image
-- uploads). Admins set this manually per shop via TENANT_EMAIL_<SHOP>
-- env vars at boot, or by UPDATEing this column directly.
ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS primary_email TEXT;

-- Case-insensitive lookup index (the box stores emails lowercase).
CREATE INDEX IF NOT EXISTS ix_tenants_primary_email
    ON public.tenants (lower(primary_email));
