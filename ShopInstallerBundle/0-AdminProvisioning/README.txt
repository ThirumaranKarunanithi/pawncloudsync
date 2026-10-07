═══════════════════════════════════════════════════════════════════════
  STEP 0 — ADMIN / CLOUD PROVISIONING
  (one-time task per shop, done by the admin BEFORE visiting the shop)
═══════════════════════════════════════════════════════════════════════

WHO RUNS THIS
   Only the admin / integrator (you). NOT the shop owner.
   The shop owner never sees this step — they just receive their
   API key inside a pre-configured sync.properties file.

WHEN TO RUN
   Before Step 3 (Sync Agent). Without this, the agent will run
   but every event it sends will be rejected with HTTP 401
   "bad api key" or 404 "tenant not configured".

WHAT THIS DOES
   1. Registers the new shop_id in the cloud database.
   2. Adds the shop owner's gmail to the email allowlist
      (needed for mobile OTP login).
   3. Generates a unique API key that the sync agent will use
      to authenticate every event upload.

────────────────────────────────────────────────────────────────────────
  PART A — Add shop to Railway env var
────────────────────────────────────────────────────────────────────────

1.  Open https://railway.app → log into your account.
2.  Open the "pawnbroking-cloud-api" project → Variables tab.
3.  Find the variable:   TENANTS
4.  Append the new shop_id (comma-separated), e.g.:
        before:  alwarpuram,annanagar,mylocal
        after :  alwarpuram,annanagar,mylocal,balamurugan
5.  Save → Railway will auto-redeploy (~3 minutes).
6.  Verify in the deployment logs:
        "Provisioning tenant schema 'balamurugan'..."
        "Flyway migrations applied to balamurugan: 3"

   The tenant schema (balamurugan.projections, balamurugan.notifications,
   etc.) is created automatically by Flyway on cloud startup.

────────────────────────────────────────────────────────────────────────
  PART B — Provision shop + generate API key (one SQL block)
────────────────────────────────────────────────────────────────────────

Connect to the cloud Postgres in Railway (Data tab → Query).
Edit the three values at the top, paste the rest as-is, then Execute:

   DO $$
   DECLARE
       v_shop_id  TEXT := 'balamurugan';
       v_email    TEXT := 'balamurugan.pawn@gmail.com';
       v_label    TEXT := 'Balamurugan shop - sync agent';
       v_api_key  TEXT;
   BEGIN
       v_api_key := 'mbk_' || translate(
                       encode(gen_random_bytes(24), 'base64'),
                       '+/=', 'xyz');

       INSERT INTO public.tenants (shop_id)
              VALUES (v_shop_id)
              ON CONFLICT DO NOTHING;

       INSERT INTO public.tenant_primary_email (shop_id, email)
              VALUES (v_shop_id, v_email)
              ON CONFLICT DO NOTHING;

       INSERT INTO public.shop_credentials (api_key, shop_id, label)
              VALUES (v_api_key, v_shop_id, v_label);

       RAISE NOTICE '======================================';
       RAISE NOTICE '  shop_id  : %', v_shop_id;
       RAISE NOTICE '  email    : %', v_email;
       RAISE NOTICE '  API KEY  : %', v_api_key;
       RAISE NOTICE '======================================';
   END $$;

Railway shows the NOTICE output in the query result panel — copy the
API key from there. It will look like:

   mbk_xK8pQ7vR2sNzL4tBwHfYqMjE9aCdGoUixyz

────────────────────────────────────────────────────────────────────────
  PART C — Write the API key into sync.properties
────────────────────────────────────────────────────────────────────────

Edit the file that will be shipped with the installer bundle:
   3-SyncAgent\sync.properties     (NOT the .sample — your real one)

Set these three lines:

   shop.id=balamurugan
   cloud.api_key=mbk_xK8pQ7vR2sNzL4tBwHfYqMjE9aCdGoUixyz
   db.password=<the PostgreSQL password the shop owner will choose>

The db.password will be filled in on-site during Step 1 (the shop
owner picks it during PostgreSQL install). Leave it blank in your
master copy or use a placeholder.

────────────────────────────────────────────────────────────────────────
  REVOKING A KEY (if compromised / shop owner leaves)
────────────────────────────────────────────────────────────────────────

   UPDATE public.shop_credentials
      SET revoked_at = now()
    WHERE api_key = 'mbk_xK8pQ7vR2sNzL4tBwHfYqMjE9aCdGoUixyz';

The next request from that key returns HTTP 401. Generate a fresh
key with the SQL block above and update the shop's sync.properties.

────────────────────────────────────────────────────────────────────────
  AUDIT — see who has keys
────────────────────────────────────────────────────────────────────────

   SELECT shop_id, label, created_at, revoked_at,
          LEFT(api_key, 10) || '...' AS key_preview
     FROM public.shop_credentials
    ORDER BY created_at DESC;

Only the first 10 chars of each key are shown — the full key was
already given to the shop owner and is never recoverable from here
(you'd just generate a new one and revoke the old).

────────────────────────────────────────────────────────────────────────
  TROUBLESHOOTING
────────────────────────────────────────────────────────────────────────

Agent log shows "HTTP 401 bad api key"
   → Key in sync.properties doesn't match any row in
     public.shop_credentials, OR the row's revoked_at is non-NULL.

Agent log shows "tenant not configured"
   → shop_id is not in Railway's TENANTS env var, OR the cloud
     hasn't been restarted since you updated the env var.

Mobile app shows "email not allowed"
   → public.tenant_primary_email is missing the row, OR the email
     in the app does not match exactly (case-sensitive). Fix:
        SELECT * FROM public.tenant_primary_email WHERE shop_id = 'balamurugan';

────────────────────────────────────────────────────────────────────────
  CHECKLIST — done with Step 0 when:
────────────────────────────────────────────────────────────────────────
   [ ] shop_id appears in Railway TENANTS env var
   [ ] Railway logs show "Provisioning tenant schema '<shop_id>'"
   [ ] public.tenants has the new row
   [ ] public.tenant_primary_email has the gmail row
   [ ] public.shop_credentials has a fresh non-revoked api_key row
   [ ] public.user_shop_access has the (email, shop_id) row
   [ ] API key copied into the bundle's 3-SyncAgent\sync.properties
   [ ] shop_id and email written down for handoff to shop owner

After all seven are checked, you're ready to visit the shop and run
Steps 1-4 on their machines.

════════════════════════════════════════════════════════════════════════
  MULTI-SHOP ACCESS — give one user access to several shops
════════════════════════════════════════════════════════════════════════

WHAT THIS IS
   If a shop owner runs more than one shop (e.g. a chain), they can
   sign into the mobile app once and switch between shops via the
   Home screen → ⋮ overflow → "Switch Shop" menu.

HOW IT WORKS
   public.user_shop_access maps an email to one or more shop_ids.
   On OTP login the cloud collects every shop the email has access to:
     • exactly 1 shop  → app goes straight to Home for that shop
     • 2+ shops        → app shows a picker; user taps the shop they
                         want to open

GRANT ACCESS TO AN ADDITIONAL SHOP
   Connect to the cloud Postgres (Railway → Data tab) and run:

      INSERT INTO public.user_shop_access (email, shop_id, role)
      VALUES ('owner@example.com', 'balamurugan', 'OWNER')
      ON CONFLICT (email, shop_id) DO NOTHING;

   No restart, no APK reinstall. On the user's next login (or next
   Switch Shop tap) the new shop appears in the picker.

   Roles available: OWNER (default) / VIEWER / AUDITOR. All three
   have the same read access today; differentiation can be added
   later if needed.

REVOKE ACCESS WITHOUT DELETING THE AUDIT TRAIL
   UPDATE public.user_shop_access
      SET revoked_at = now()
    WHERE email = 'owner@example.com' AND shop_id = 'balamurugan';

   The next request from that user for that shop returns HTTP 403.
   Reactivate later by clearing revoked_at to NULL.

AUDIT — see who has access to what
   SELECT email, shop_id, role, added_at, revoked_at
     FROM public.user_shop_access
    ORDER BY email, shop_id;

────────────────────────────────────────────────────────────────────────
  TROUBLESHOOTING — multi-shop edge cases
────────────────────────────────────────────────────────────────────────

Picker shows fewer shops than expected
   1. Confirm the row exists:
        SELECT * FROM public.user_shop_access
         WHERE lower(email) = 'owner@example.com';
   2. Confirm both tenants are active:
        SELECT shop_id, active FROM public.tenants;
   3. Confirm no revoked_at:
        SELECT * FROM public.user_shop_access
         WHERE email = 'owner@example.com' AND revoked_at IS NOT NULL;

Picker shows "Forbidden" when tapping a shop
   Cloud-api is older than the multi-shop /select-shop fix. Verify
   Railway is on the latest deploy.

User can't tap Switch Shop at all
   They're using an older APK without the menu item. Reinstall from
   4-MobileApp\Pawnbroking.apk in this bundle.
