-- =====================================================================
--  MANONMANI - CLOUD PROVISIONING
--  shop_id: manonmani
--  Run on Railway -> your cloud service -> Data -> Query.
--  Run each statement ONE AT A TIME (the console splits on ';').
--
--  Manonmani already runs the DESKTOP app (both its machines are in the
--  licence list). This adds the CLOUD side, which is what the mobile app
--  and a Meet "Pawn Shop" room read from. The shop PC half is
--  manonmani_setup.sql.
--
--  ORDER            1. This file, R1 to R6           (gets the sync key)
--                   2. Install the sync agent on the shop PC
--                   3. manonmani_setup.sql, F5 on the shop PC
--                   4. manonmani_cloud_verify.sql    (once the queue drains)
--
--  Safe to paste whole and safe to run again: nothing here is added twice.
--
--  One address may sign in:
--     marunganathan@gmail.com   the owner, this shop only
--
--  Manonmani is its own business, not a Rajeshwari branch, so - as with
--  balamurugan - the two Rajeshwari admin addresses are NOT given access.
--  R3b adds your own address if you want to see this shop on your phone.
--
--  The owner's inbox must be reachable - the sign-in code goes there, and
--  that same first login is what mints the box token photos need.
--
--  The owner's email must ALSO have a Magizhchi Share account - Share is
--  what sends the code. Without one, "Send OTP" in the phone app says
--  just "Not Found". Sign up once at https://boxapp.magizhchi.software
--  (name, mobile number, this same email), then Send OTP works.
-- =====================================================================


-- R1  Register the tenant. schema_name + display_name are NOT NULL.
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES ('manonmani', 'manonmani', 'Manonmani Pawn Broking')
ON CONFLICT DO NOTHING;


-- R2  The legacy primary_email column. user_shop_access below is what
--     actually gates login; this keeps the tenant row complete.
UPDATE public.tenants SET primary_email = 'marunganathan@gmail.com'
WHERE shop_id = 'manonmani';


-- R3  OWNER - the address that signs in on the phone.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES ('marunganathan@gmail.com', 'manonmani', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- R3b OPTIONAL - only if you want this shop on your own phone for support.
--     Remove the two dashes and run it. Let the owner sign in FIRST: the
--     box token is minted by a login from an address that holds only
--     this shop.
-- INSERT INTO public.user_shop_access (email, shop_id, role) VALUES ('tirukaruna@gmail.com', 'manonmani', 'OWNER') ON CONFLICT (email, shop_id) DO NOTHING;


-- R4  (Railway UI, not SQL) Add  manonmani  to the TENANTS variable,
--     comma-separated, and let it redeploy (~3 min). Deploy Logs should
--     show:  Provisioning tenant schema 'manonmani'


-- R5  The sync key. Copy the mbk_... it returns - it goes into the sync
--     agent setup on the shop PC as the Cloud API key. It makes a key
--     only if manonmani has none, so running it again is safe: it then
--     returns nothing, and the existing key (stored in plain text) can be
--     read back with:
--       SELECT api_key, created_at FROM public.shop_credentials
--        WHERE shop_id = 'manonmani' AND revoked_at IS NULL;
INSERT INTO public.shop_credentials (api_key, shop_id, label)
SELECT 'mbk_' || replace(gen_random_uuid()::text,'-','')
              || replace(gen_random_uuid()::text,'-',''),
       'manonmani', 'Manonmani shop - sync agent'
 WHERE NOT EXISTS (SELECT 1 FROM public.shop_credentials
                    WHERE shop_id = 'manonmani' AND revoked_at IS NULL)
RETURNING api_key;


-- R6  Check. Expect 1 tenant, the owner email, 1 sign-in (2 if you ran
--     R3b), 1 api key - and schema "ok" once R4's redeploy has finished.
--     Do not start the sync agent while schema says NOT YET.
SELECT 'tenant'  AS what, shop_id                          AS value FROM public.tenants          WHERE shop_id='manonmani'
UNION ALL
SELECT 'email',  COALESCE(primary_email,'(none)')                  FROM public.tenants          WHERE shop_id='manonmani'
UNION ALL
SELECT 'access', email || ' (' || role || ')'                      FROM public.user_shop_access WHERE shop_id='manonmani' AND revoked_at IS NULL
UNION ALL
SELECT 'api_key',label                                             FROM public.shop_credentials WHERE shop_id='manonmani' AND revoked_at IS NULL
UNION ALL
SELECT 'schema', CASE WHEN to_regnamespace('manonmani') IS NULL
                      THEN 'NOT YET - do R4 (TENANTS) and wait for the redeploy'
                      ELSE 'ok' END;


-- =====================================================================
--  LATER: once the shop PC's report says 0 waiting, run
--  manonmani_cloud_verify.sql here to compare the cloud with the desktop.
--
--  AFTER THAT it can have its own room in Meet: room type PAWN_SHOP,
--  "Pawn shop this room shows" = manonmani, and the owner's address
--  added to that room and promoted to ADMIN (Super Admin cannot be given
--  to anyone - it is always whoever created the room; Admin is enough to
--  publish, and reaches only this room). If the shop has a CCTV recorder,
--  tick "This room has its own recorder" on that room.
--
--  NOTE for later: add_secondary_emails_all_shops_except_balamurugan.sql
--  grants the two Rajeshwari admins to EVERY shop except balamurugan.
--  Re-running it as it stands would give them manonmani too.
-- =====================================================================
