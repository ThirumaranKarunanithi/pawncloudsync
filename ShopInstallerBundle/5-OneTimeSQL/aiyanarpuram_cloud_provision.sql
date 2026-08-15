-- =====================================================================
--  AIYANARPURAM — CLOUD PROVISIONING
--  Run on Railway -> your cloud service -> Data -> Query.
--  Run each statement ONE AT A TIME (the console splits on ';').
--
--  Aiyanarpuram already runs the DESKTOP app (its machine is in the
--  licence list). This adds the CLOUD side, which is what the mobile app
--  and a Meet "Pawn Shop" room read from. Until this is done the shop
--  works normally on its own PC and is simply invisible everywhere else.
--
--  ONE THING TO FILL IN: the owner's gmail on line marked <-- EDIT.
--  That address is how the owner signs in on the phone, and it must be
--  an address they can actually receive mail at — the OTP goes there.
-- =====================================================================


-- 1. Register the tenant. schema_name + display_name are NOT NULL.
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES ('aiyanarpuram', 'aiyanarpuram', 'Aiyanarpuram Pawn Broking')
ON CONFLICT DO NOTHING;


-- 2. The legacy primary_email column. user_shop_access below is what
--    actually gates login; this keeps the tenant row complete.
UPDATE public.tenants SET primary_email = 'OWNER_EMAIL_HERE@gmail.com'   -- <-- EDIT
WHERE shop_id = 'aiyanarpuram';


-- 3a. OWNER — the address that signs in on the phone.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('OWNER_EMAIL_HERE@gmail.com', 'aiyanarpuram', 'OWNER')            -- <-- EDIT
ON CONFLICT (email, shop_id) DO NOTHING;


-- 3b. The two admin addresses that hold every Rajeshwari branch.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('tirukaruna@gmail.com', 'aiyanarpuram', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('neelamanikandank@gmail.com', 'aiyanarpuram', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- 4. The sync key. COPY the returned mbk_... — it goes into the shop
--    PC's sync.properties and cannot be read back afterwards.
INSERT INTO public.shop_credentials (api_key, shop_id, label)
VALUES ('mbk_' || replace(gen_random_uuid()::text,'-','')
              || replace(gen_random_uuid()::text,'-',''),
        'aiyanarpuram', 'Aiyanarpuram shop - sync agent')
RETURNING api_key;


-- 5. (Railway UI, not SQL) Add  aiyanarpuram  to the TENANTS variable,
--    comma-separated, and let it redeploy (~3 min). Confirm in Deploy
--    Logs:  "Provisioning tenant schema 'aiyanarpuram'".


-- ── VERIFY ───────────────────────────────────────────────────────────
SELECT 'tenant'  AS what, shop_id                          AS value FROM public.tenants        WHERE shop_id='aiyanarpuram'
UNION ALL
SELECT 'email',  COALESCE(primary_email,'(none)')                  FROM public.tenants        WHERE shop_id='aiyanarpuram'
UNION ALL
SELECT 'access', email || ' (' || role || ')'                      FROM public.user_shop_access WHERE shop_id='aiyanarpuram' AND revoked_at IS NULL
UNION ALL
SELECT 'api_key',label                                             FROM public.shop_credentials WHERE shop_id='aiyanarpuram' AND revoked_at IS NULL;


-- =====================================================================
--  THEN, ON THE SHOP PC  (see 3-SyncAgent\ROLLOUT_EXISTING_SHOPS.txt)
--
--    1. Install the sync agent, and put the mbk_ key from step 4 into
--       C:\ProgramData\PawnBroking\sync.properties with
--           shop.id=aiyanarpuram
--           batch.size=25          <-- not 200; 200 stalls on a backlog
--    2. Run re_plus_customer_pricing_dated.sql on the local pawnbroking
--       database if this shop uses Re+ customers.
--    3. Existing bills do NOT sync by themselves — triggers only capture
--       changes. Run initial_full_backfill.sql (shop_id aiyanarpuram) to
--       push the history, and expect it to take a while.
--    4. Sign in once on the phone with the owner's email. That mints the
--       Magizhchi box token; until it exists every image upload answers
--       503 and no photo reaches the cloud.
--
--  AFTER THAT it can have its own room in Meet: room type PAWN_SHOP,
--  "Pawn shop this room shows" = aiyanarpuram, and the owner's address
--  as that room's Super Admin.
-- =====================================================================
