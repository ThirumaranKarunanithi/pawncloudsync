╔═════════════════════════════════════════════════════════════════════╗
║                                                                     ║
║         PAWNBROKING — NEW SHOP INSTALLER BUNDLE                     ║
║         Complete setup guide for a fresh Windows machine            ║
║                                                                     ║
╚═════════════════════════════════════════════════════════════════════╝

This bundle contains EVERYTHING needed to set up a new shop on a
new computer + connect their phone. Follow the folders in order —
each has its own README.txt with detailed instructions.

For a screenshot-rich walkthrough, see  New_Shop_Onboarding.pdf
(in this same folder).

────────────────────────────────────────────────────────────────────────
  AT A GLANCE — what gets installed where
────────────────────────────────────────────────────────────────────────

  ┌─────────────────────────────────────────────────────────────────┐
  │  SHOP PC (Windows)                                              │
  │                                                                 │
  │   1. Java Runtime 17     (downloaded from Adoptium)             │
  │   2. PostgreSQL 16       (downloaded from postgresql.org)       │
  │   3. PawnBroking.exe     (this bundle / 2-DesktopApp)           │
  │   4. Pawnbroking Sync    (this bundle / 3-SyncAgent — service)  │
  │                                                                 │
  └────────────────────────────┬────────────────────────────────────┘
                                │ (sync agent uploads events)
                                ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  CLOUD (already deployed — admin task)                          │
  │                                                                 │
  │   • Cloud-API @ devpawn.magizhchi.academy (Railway)             │
  │   • Magizhchi Share @ box.magizhchi.software (OTP + storage)    │
  │                                                                 │
  └────────────────────────────┬────────────────────────────────────┘
                                │ (mobile app reads from cloud)
                                ▼
  ┌─────────────────────────────────────────────────────────────────┐
  │  SHOP OWNER'S PHONE (Android)                                   │
  │                                                                 │
  │   5. Pawnbroking.apk     (this bundle / 4-MobileApp)            │
  │                                                                 │
  └─────────────────────────────────────────────────────────────────┘

────────────────────────────────────────────────────────────────────────
  STEP-BY-STEP — do these in order
────────────────────────────────────────────────────────────────────────

  STEP 0 — Admin / Cloud Provisioning   (you, before visiting shop)
       Open  0-AdminProvisioning\README.txt
       Add shop_id to Railway TENANTS env var, insert tenant +
       primary_email rows, generate the unique API key for this
       shop, paste it into the bundle's sync.properties.
       Without this, Step 3 will fail with HTTP 401.

  STEP 1 — Prerequisites
       Open  1-Prerequisites\README.txt
       Install Java 17 + PostgreSQL 16. Verify both work.

  STEP 2 — Desktop App
       Open  2-DesktopApp\README.txt
       Run PawnBrokingSetup.exe. First-launch wizard creates the DB
       schema. Create the shop's first company (CMP1).

  STEP 3 — Sync Agent
       Open  3-SyncAgent\README.txt
       Copy folder to C:\Program Files\PawnbrokingSync\,
       fill in sync.properties (db.password + the rest is already
       set from Step 0), run install-service.bat as ADMIN.

       ►► Step 0 must be done BEFORE this step ◄◄
       Otherwise the agent will run but events will be rejected
       with "tenant not configured" or "bad api key".

  STEP 4 — Mobile App
       Open  4-MobileApp\README.txt
       Send Pawnbroking.apk to the shop owner's phone. Install.
       Login with the gmail address added in Step 3.

  STEP 5 — One-time SQL  (only if something looks empty)
       Open  5-OneTimeSQL\README.txt
       Run these scripts only if the mobile app shows ₹0 for
       sections that clearly have data on the desktop.

────────────────────────────────────────────────────────────────────────
  TYPICAL TIMINGS
────────────────────────────────────────────────────────────────────────
   Step 0  (admin / cloud prov)  ..  5 min  (done before visiting)
   Step 1  (prerequisites)       .. 15-30 min (PostgreSQL is slow)
   Step 2  (desktop)             ..  5 min
   Step 3  (sync agent)          ..  5 min
   Step 4  (mobile app)          ..  3 min
   Step 5  (replays, if needed)  ..  2 min
                                   ─────────
   Total                           ~35-50 min for a brand-new shop

────────────────────────────────────────────────────────────────────────
  WHAT TO PROVIDE THE SHOP BEFORE YOU LEAVE
────────────────────────────────────────────────────────────────────────
   ✓ Their shop_id              (e.g. "balamurugan")
   ✓ The gmail address          (used for mobile OTP login)
   ✓ The PostgreSQL password    (in case they need to reinstall)
   ✓ The cloud API key          (already in sync.properties)
   ✓ Phone number / email for support

────────────────────────────────────────────────────────────────────────
  TROUBLESHOOTING QUICK REFERENCE
────────────────────────────────────────────────────────────────────────

  Desktop app won't open
     → Check Java is installed:  java -version
     → Check PostgreSQL is running:  Start menu → "Services" →
        find "postgresql-x64-16" → should be "Running".

  Sync Agent service won't start
     → Check pawnbroking-sync.err.log in the sync agent folder.
     → Most common cause: wrong db.password in sync.properties.

  Mobile app shows "Too many OTP requests"
     → Wait 60 seconds. The box rate-limits OTP send to one per
        minute per email.

  Mobile app shows "tenant not configured"
     → Admin hasn't added the shop_id to the cloud's TENANTS env
        var. See Step 3 admin action.

  Mobile app shows ₹0 / blank for a section that has data
     → Run the relevant replay SQL from Step 5.

  Mobile app shows old data, missing latest bills
     → Sync agent isn't running. Open services.msc → start
        "Pawnbroking Sync Agent".
