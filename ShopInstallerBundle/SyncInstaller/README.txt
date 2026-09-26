=======================================================================
  PawnBrokingSyncSetup.exe  -  the shop PC, in one run
  dist\PawnBrokingSyncSetup.exe      Right-click -> Run as administrator
=======================================================================

THREE MODES — a working shop is never touched by accident

  When the exe starts on a PC that ALREADY has a shop on it, the first
  thing it asks is how far it may go. "Check only" is pre-selected.

     CHECK ONLY     Looks. Prints a report. Changes NOTHING - not one
                    file is copied, the service is not stopped, the
                    config is not rewritten, and not one SQL statement
                    writes. It runs from a temp folder against the Java
                    already on the PC and leaves the report in TEMP.
                    This is the one to use at a shop that is working.

     BACKUPS ONLY   Reads everything, and may change only the backup
                    side: backup.retention.days (one line of the config)
                    and the agent's own record of which backups it has
                    uploaded. Both are OFF unless you tick them, so with
                    nothing ticked this mode is also look-only. Bills,
                    photos, triggers, the history and every other
                    setting are left exactly as they are.

     FULL SETUP     The whole thing, below. For a new shop, or a repair.

  A brand-new PC never sees that page - there, the full setup is the
  only sensible thing.

  From the Start menu afterwards: "Check only (changes nothing)",
  "Check the backup files", "Run setup again" (full).


WHAT THE FULL SETUP DOES

  One exe, same one for every shop, new or existing. It asks four things
  and then does the whole shop PC side by itself:

     THE AGENT     installs the jar, the service wrapper and its own Java
                   into C:\pawnbrokingSync, registers the Windows service
                   "pawnbroking-sync" and starts it

     THE CONFIG    writes C:\ProgramData\PawnBroking\sync.properties -
                   MERGING, not overwriting: anything the shop already
                   had is kept, missing settings get their defaults, and
                   a batch.size over 50 is corrected to 25 (200 stalls
                   the queue on a backlog and reads like a dead agent)

     THE DATABASE  sync_outbox, the pk-aware capture function and a
                   trigger on every table; the SUSPENSE bill status and
                   the suspense table; the Re+ pricing columns and
                   customer_pricing; the notice mode column; a primary
                   key on repledge_billing so repledges cannot collapse
                   to one row on the cloud

     THE HISTORY   sends every existing bill, customer, repledge and day
                   account to the cloud - ONCE. The send is marked on
                   sync_outbox, so running the exe again never repeats it

     THE FILES     checks that the photo folders
                   (company_other_settings.camera_temp_file_name) and the
                   backup folders (company.backup_file_path) exist on THIS
                   PC and says how many files are in them. A folder that
                   came from the old machine's drive letter is named in
                   the report - photos cannot upload until it is there

     THE PROOF     talks to the cloud with the shop's key before anything
                   else, so a wrong mbk_ key or a shop still missing from
                   the TENANTS variable is a sentence on the screen, not
                   a queue that silently never drains

  It leaves a report at  C:\pawnbrokingSync\logs\setup-report.txt  and two
  shortcuts: "Sync progress report" (how much has reached the cloud) and
  "Run setup again".


BEFORE YOU GO TO THE SHOP

  The cloud half must be done first - the shop's own
  <shopid>_cloud_provision.sql in ..\5-OneTimeSQL, run on Railway. Take
  the mbk_ key it returns (R5) with you. R6 must say schema "ok"; if it
  says NOT YET, the shop is not in the TENANTS variable yet and the agent
  would be refused.

  On the shop PC: Java is NOT needed (the exe brings its own), but
  PostgreSQL and the desktop app must already be there, with the shop's
  database restored and the image and backup folders copied to the paths
  the database points at.


WHAT IT ASKS

     Shop ID        lowercase, e.g. dhineshsuganya
     Cloud API key  the mbk_ key from R5
     Cloud URL      leave the default
     DB user        usually postgres
     DB password    the one pgAdmin connects with on that PC

  On a shop that already has the agent every box is filled in from the
  PC itself - Next, Next, Install, nothing typed. Type a DIFFERENT shop
  id and it asks whether you really mean it: that points the shop's data
  at another tenant.


RUNNING IT ON AN EXISTING SHOP

  Start with CHECK ONLY. It tells you what is there, what is behind, and
  what is wrong, without changing anything. Decide from the report.

  If only the backups are wrong, run it again and choose BACKUPS ONLY.
  What it can put right there:

     files never uploaded because they are older than the retention
     window        -> tick "Also upload backups older than ..." (sets
                      backup.retention.days = 0 and restarts the service)

     the cloud is missing a file this PC believes it sent
                   -> tick "Upload every backup again" (forgets the
                      upload records; the cloud REPLACES a file uploaded
                      twice, so nothing is duplicated there)

     the folder in Company Module does not exist on this PC, the folder
     is empty, or the desktop backup job has stopped writing
                   -> named in the report; the exe does not invent a
                      folder or a backup file

     401 / 503 / 507 / 502 in the agent's log
                   -> explained in the report in plain words. 502 means
                      that PC still has the pre-gzip agent and needs a
                      FULL setup to fix big backups

  FULL SETUP on an existing shop is the right way to update one: it
  stops the service, swaps the jar, keeps the config (a timestamped .bak
  beside it), repairs anything missing in the database, and does NOT
  resend the history. A service someone registered by hand from another
  folder is taken over: the old one is stopped and unregistered first.

  The one thing FULL will do on an existing shop that never sent its
  history is send it. That locks billing for a few minutes on a big
  shop, so run it when nobody is at the counter.


WHEN TO RUN IT AGAIN

     after restoring the database        (triggers come back)
     after moving the shop to a new PC
     when the agent is updated
     when support asks

  Or use the "Run setup again" shortcut, which is the same thing without
  reinstalling. It takes options:

     run-setup.bat --history skip     everything except the history
     run-setup.bat --history force    send the whole history again


BUILDING IT

     build-installer.bat

  Needs JDK 17 (jlink), Inno Setup 6, and a built agent jar:

     cd ..\..\pawnbroking-sync-agent && mvn clean package

  The bat jlinks a minimal JRE into stage\runtime, stages the jar and
  WinSW, and compiles installer.iss into dist\PawnBrokingSyncSetup.exe
  (about 34 MB).

  Files that ship beside the agent: run-setup.bat, upload-progress.ps1,
  update-agent.bat, uninstall-service.bat, sync.properties.template.
  upload-progress.ps1 is a copy of the one in ..\3-SyncAgent - update
  both, or copy it over before building.

  A note for whoever edits installer.iss: never let a line START with
  "[" (a Format(...) argument list wrapped onto its own line will), or
  the compile dies with "Invalid section tag".


WHAT IT DOES NOT DO

  PostgreSQL, the desktop app, and copying the image and backup folders
  from the old machine are still separate steps - it checks them and
  tells you, but it will not install them or invent a folder that should
  have come off the old PC.

  It does not sign the shop in on the phone, and until someone does that
  once, every photo and backup upload is refused with 503. That first
  sign-in is what mints the box token.
