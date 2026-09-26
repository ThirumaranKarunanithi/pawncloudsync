-- =====================================================================
--  KARUMBALAI — CLOUD PROVISIONING
--  Run on Railway -> your cloud service -> Data -> Query.
--  Run each statement ONE AT A TIME (the Railway console splits on ';').
--
--  Owner (primary) login : rajeshwarikarumbalai@gmail.com
--  Secondary login       : tirukaruna@gmail.com
--  Both can OTP-sign-in on the phone and see the Karumbalai shop.
-- =====================================================================


-- 1. Register the tenant. schema_name + display_name are NOT NULL.
--    (schema_name = shop_id keeps it simple and matches how the cloud
--     auto-provisions tenants from the TENANTS env var.)
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES ('karumbalai', 'karumbalai', 'Karumbalai Pawn Broking')
ON CONFLICT DO NOTHING;


-- 2. Set the legacy primary_email (it's a COLUMN on tenants, not a table).
--    Not strictly required for login anymore (user_shop_access below is the
--    real gate) but keeps the tenant row complete.
UPDATE public.tenants SET primary_email = 'rajeshwarikarumbalai@gmail.com'
WHERE shop_id = 'karumbalai';


-- 3a. PRIMARY email — grant login access to this shop.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('rajeshwarikarumbalai@gmail.com', 'karumbalai', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- 3b. SECONDARY email — also logs in to this shop.
--     (Every email in user_shop_access for this shop_id can sign in.)
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('tirukaruna@gmail.com', 'karumbalai', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- 4. Generate the sync API key.  COPY the returned mbk_... value —
--    it goes into the shop PC's sync.properties and cannot be retrieved later.
INSERT INTO public.shop_credentials (api_key, shop_id, label)
VALUES ('mbk_' || replace(gen_random_uuid()::text,'-','')
              || replace(gen_random_uuid()::text,'-',''),
        'karumbalai', 'Karumbalai shop - sync agent')
RETURNING api_key;


-- 5. (In the Railway UI, not SQL) Add  karumbalai  to the TENANTS
--    environment variable (comma-separated), then let it redeploy (~3 min).
--    Confirm in Deploy Logs:  "Provisioning tenant schema 'karumbalai'".


-- =====================================================================
--  ADDING MORE SECONDARY EMAILS LATER
--  Just run ONE more INSERT per person (they can sign in immediately —
--  no redeploy, no APK change). Role can be OWNER / VIEWER / AUDITOR.
-- =====================================================================
-- INSERT INTO public.user_shop_access (email, shop_id, role)
-- VALUES ('another_person@gmail.com', 'karumbalai', 'OWNER')
-- ON CONFLICT (email, shop_id) DO NOTHING;
--
--  To REMOVE someone's access later (soft-revoke, keeps history):
-- UPDATE public.user_shop_access SET revoked_at = now()
--  WHERE shop_id = 'karumbalai' AND lower(email) = 'another_person@gmail.com';


-- ── VERIFY everything is in place ────────────────────────────────────
SELECT 'tenant'  AS what, shop_id                     AS value FROM public.tenants        WHERE shop_id='karumbalai'
UNION ALL
SELECT 'email',  COALESCE(primary_email,'(none)')            FROM public.tenants        WHERE shop_id='karumbalai'
UNION ALL
SELECT 'access', email || ' (' || role || ')'               FROM public.user_shop_access WHERE shop_id='karumbalai' AND revoked_at IS NULL
UNION ALL
SELECT 'api_key',label                                      FROM public.shop_credentials WHERE shop_id='karumbalai' AND revoked_at IS NULL;
