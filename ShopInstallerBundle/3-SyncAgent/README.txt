═══════════════════════════════════════════════════════════════════════
  STEP 3 — SYNC AGENT (background uploader to cloud + Magizhchi box)
═══════════════════════════════════════════════════════════════════════

WHAT THIS IS
   A small Windows service that runs silently in the background. It
   watches the local PostgreSQL database for changes and ships them to
   the Magizhchi cloud so the mobile app can see live data. It also
   uploads bill images and the daily backup folder to Magizhchi Share.

WHAT IS IN THIS FOLDER
   pawnbroking-sync-agent.jar   ← the actual program
   pawnbroking-sync.exe         ← Windows service wrapper (winsw)
   pawnbroking-sync.xml         ← service config (don't edit)
   sync.properties.sample       ← config template — DO NOT install this
                                  as-is. Copy it first (see step 1 below).
   install-service.bat          ← run AS ADMIN after configuring

────────────────────────────────────────────────────────────────────────
INSTALLATION  (do these in order)
────────────────────────────────────────────────────────────────────────

1.  Copy this WHOLE folder to a permanent location, e.g.
       C:\Program Files\PawnbrokingSync\

2.  Make a copy of sync.properties.sample called sync.properties (drop
    the ".sample"). Open it in Notepad and fill in:

       db.password=<the PostgreSQL password you set in Step 1.B>
       shop.id=<your shop_id — assign one, e.g. "balamurugan">
       cloud.api_key=<we will give you this per-shop key>

    Leave the rest (cloud.url, batch.size, etc.) untouched unless
    instructed otherwise.

3.  ASK CLAUDE / THE ADMIN TO PROVISION THIS SHOP IN THE CLOUD:
       • Add shop_id to the cloud's TENANTS env var on Railway
       • Add a primary_email row in the public.tenant_primary_email
         table for the shop owner's gmail address
       • Restart the cloud-api once so Flyway provisions the new
         tenant schema

    Without this step, the sync agent will run but its events will be
    rejected by the cloud with "tenant not configured".

4.  RIGHT-CLICK install-service.bat → Run as administrator.
    It will register "pawnbroking-sync" as a Windows service that
    starts automatically with Windows.

5.  Verify the service is running:
       Open services.msc → look for "Pawnbroking Sync Agent" → status
       should be "Running".  OR run from the folder:
          pawnbroking-sync.exe status

   The first time it starts it will create the sync_outbox table and
   triggers in your DB automatically (SchemaGuard). No manual psql.

────────────────────────────────────────────────────────────────────────
LOGS  (if anything goes wrong, check these first)
────────────────────────────────────────────────────────────────────────
   pawnbroking-sync.wrapper.log   ← service lifecycle (start/stop)
   pawnbroking-sync.out.log       ← normal output (every event sent)
   pawnbroking-sync.err.log       ← errors (cloud rejections, DB issues)

────────────────────────────────────────────────────────────────────────
DAILY BEHAVIOUR
────────────────────────────────────────────────────────────────────────
   • Polls the local DB every 5 seconds for new events
   • Batches 200 events at a time → POSTs to cloud
   • Watches the bill-image folder once per minute → uploads new files
   • Watches the backup folder → uploads new backup files
   • All throttled to respect the Magizhchi box's 60 requests/minute

If internet is down, events accumulate locally and ship as soon as the
connection returns. Nothing is lost.

────────────────────────────────────────────────────────────────────────
MAINTENANCE COMMANDS  (from this folder, in a Command Prompt)
────────────────────────────────────────────────────────────────────────
   pawnbroking-sync.exe status      ← is it running?
   pawnbroking-sync.exe stop        ← stop the service
   pawnbroking-sync.exe start       ← start the service
   pawnbroking-sync.exe uninstall   ← remove the service entirely
