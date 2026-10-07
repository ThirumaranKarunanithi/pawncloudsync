═══════════════════════════════════════════════════════════════════════
  STEP 1 — PREREQUISITES (install BEFORE everything else)
═══════════════════════════════════════════════════════════════════════

These two free programs MUST be installed first. Anything below will
fail to start if they are missing.

────────────────────────────────────────────────────────────────────────
A. Java Runtime 17 (for the desktop app + sync service)
────────────────────────────────────────────────────────────────────────
Download:
   https://adoptium.net/temurin/releases/?version=17&os=windows

Pick:  Windows · x64 · JDK 17 · .msi installer
Run the .msi → accept defaults → tick "Add to PATH" if asked.

Verify (open Command Prompt):
   java -version
Expected output: "openjdk version "17.0.x"..."

────────────────────────────────────────────────────────────────────────
B. PostgreSQL 16 (the local database)
────────────────────────────────────────────────────────────────────────
Download:
   https://www.postgresql.org/download/windows/
   → click "Download the installer" (EDB) → pick PostgreSQL 16 · Windows x64.

During the install wizard:
   • Components: keep everything ticked (Server + pgAdmin 4 + Stack Builder)
   • Data directory: leave default
   • PASSWORD for the "postgres" user: PICK ONE AND WRITE IT DOWN
                                       (you will need it in step 3)
   • Port: 5432  (leave default)
   • Locale: Default
   • Skip Stack Builder at the end

After install, open Start menu → "pgAdmin 4" → connect with the
password you just set. You should see a database tree on the left.

────────────────────────────────────────────────────────────────────────
C. (recommended) Modern Chrome / Edge browser for pgAdmin
────────────────────────────────────────────────────────────────────────
pgAdmin opens in your default browser. Make sure it is up to date.

Once both Java AND PostgreSQL are installed and verified, move on to
step 2 (Desktop App).
