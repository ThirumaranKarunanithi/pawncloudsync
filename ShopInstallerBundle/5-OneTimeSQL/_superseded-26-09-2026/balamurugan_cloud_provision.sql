-- =====================================================================
--  BALAMURUGAN — CLOUD PROVISIONING
--  Run on Railway -> your cloud service -> Data -> Query.
--  Run each statement ONE AT A TIME (the Railway console splits on ';').
--
--  EDIT the email on lines marked  <-- EDIT  to the shop owner's gmail.
-- =====================================================================


-- 1. Register the tenant. schema_name + display_name are NOT NULL.
--    (schema_name = shop_id keeps it simple and matches how the cloud
--     auto-provisions tenants from the TENANTS env var.)
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES ('balamurugan', 'balamurugan', 'Balamurugan Pawn Broking')
ON CONFLICT DO NOTHING;


-- 2. Set the legacy primary_email (it's a COLUMN on tenants, not a table).
--    Not strictly required for login anymore (user_shop_access below is the
--    real gate) but keeps the tenant row complete.
UPDATE public.tenants SET primary_email = 'bala251282@gmail.com'
WHERE shop_id = 'balamurugan';


-- 3. Grant that email access to this shop (drives the mobile login).
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('bala251282@gmail.com', 'balamurugan', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- 4. Generate the sync API key.  COPY the returned mbk_... value —
--    it goes into the shop PC's sync.properties and cannot be retrieved later.
INSERT INTO public.shop_credentials (api_key, shop_id, label)
VALUES ('mbk_' || replace(gen_random_uuid()::text,'-','')
              || replace(gen_random_uuid()::text,'-',''),
        'balamurugan', 'Balamurugan shop - sync agent')
RETURNING api_key;


-- 5. (In the Railway UI, not SQL) Add  balamurugan  to the TENANTS
--    environment variable (comma-separated), then let it redeploy (~3 min).
--    Confirm in Deploy Logs:  "Provisioning tenant schema 'balamurugan'".


-- ── VERIFY everything is in place ────────────────────────────────────
SELECT 'tenant'   AS what, shop_id       AS value FROM public.tenants         WHERE shop_id='balamurugan'
UNION ALL
SELECT 'email',   COALESCE(primary_email,'(none)') FROM public.tenants         WHERE shop_id='balamurugan'
UNION ALL
SELECT 'access',  email                  FROM public.user_shop_access          WHERE shop_id='balamurugan' AND revoked_at IS NULL
UNION ALL
SELECT 'api_key', label                  FROM public.shop_credentials          WHERE shop_id='balamurugan' AND revoked_at IS NULL;
