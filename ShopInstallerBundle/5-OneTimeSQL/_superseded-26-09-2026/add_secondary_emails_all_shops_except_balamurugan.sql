-- =====================================================================
--  BULK SECONDARY-EMAIL GRANT
--  Adds two secondary logins to EVERY shop EXCEPT balamurugan.
--
--  Secondary emails being added:
--     tirukaruna@gmail.com
--     neelamanikandank@gmail.com
--
--  Idempotent — "if already exists, leave it": ON CONFLICT DO NOTHING
--  skips any (email, shop) grant that already exists, so it is safe to
--  re-run and it will not disturb existing access.
--
--  Set-based: it reads the shop list from public.tenants, so it covers
--  ALL current shops automatically (no hardcoded shop names to keep in
--  sync). New shops added later are NOT retro-covered — re-run this
--  script after adding a shop if you want these two on it too.
--
--  Run on Railway -> your cloud service -> Data -> Query.
--  Each block below is a SINGLE statement (safe for the console).
--  NOTE: run karumbalai_cloud_provision.sql FIRST if karumbalai should
--        be included — a shop must exist in public.tenants to be covered.
-- =====================================================================


-- 0. PREVIEW (optional) — which shops will be affected?
SELECT shop_id, display_name
  FROM public.tenants
 WHERE shop_id <> 'balamurugan'
 ORDER BY shop_id;


-- 1. Grant  tirukaruna@gmail.com  to every shop except balamurugan.
INSERT INTO public.user_shop_access (email, shop_id, role)
SELECT 'tirukaruna@gmail.com', shop_id, 'OWNER'
  FROM public.tenants
 WHERE shop_id <> 'balamurugan'
ON CONFLICT (email, shop_id) DO NOTHING;


-- 2. Grant  neelamanikandank@gmail.com  to every shop except balamurugan.
INSERT INTO public.user_shop_access (email, shop_id, role)
SELECT 'neelamanikandank@gmail.com', shop_id, 'OWNER'
  FROM public.tenants
 WHERE shop_id <> 'balamurugan'
ON CONFLICT (email, shop_id) DO NOTHING;


-- ── VERIFY — every shop these two emails can now access ──────────────
SELECT email, shop_id, role
  FROM public.user_shop_access
 WHERE lower(email) IN ('tirukaruna@gmail.com','neelamanikandank@gmail.com')
   AND revoked_at IS NULL
 ORDER BY email, shop_id;
