═══════════════════════════════════════════════════════════════════════
  STEP 5 — ONE-TIME REPLAY SQL  (only run if needed)
═══════════════════════════════════════════════════════════════════════

WHAT THIS IS
   When a shop is migrated to the new system, some existing tables
   already have data — but the sync_capture trigger was added later,
   so those rows never generated sync events. These SQL scripts force
   every existing row to re-fire the trigger, shipping them to the
   cloud in one go.

WHEN TO RUN THEM
   ONLY run a script after:
     1. The Sync Agent (Step 3) is installed and running.
     2. The shop has been provisioned in the cloud (TENANTS env var).
     3. You see ZEROS in the mobile app for that section even though
        the desktop clearly has data.

HOW TO RUN
   1. Open pgAdmin → connect to the local PostgreSQL.
   2. Open the relevant .sql file from this folder.
   3. EDIT the line `SET LOCAL app.shop_id = 'mylocal';` to your
      shop's actual shop_id (e.g. 'balamurugan').
   4. Click "Execute" (F5).
   5. Wait ~30 seconds — the sync agent will flush the new events to
      the cloud. Reopen the mobile app screen to verify.

────────────────────────────────────────────────────────────────────────
WHAT EACH SCRIPT DOES
────────────────────────────────────────────────────────────────────────

   replay_todays_account.sql
      Backfills:  company_todays_account
                  company_todays_account_available_amount
                  company_advance_amount
      Run this if Today's Account shows ₹0 for everything despite
      desktop having day-close data.

   replay_other_debit_credit.sql
      Backfills:  company_other_debit  (Expenses)
                  company_other_credit (Incomes)
      Run this if the EXPENSES / INCOMES rows on Today's Account
      show 0 / 0 even though desktop has expense entries.

   replay_expense_income.sql
      Generic auto-detect script. Finds any table whose name contains
      "expense", "income", "entry", or "voucher" and replays it.
      Useful if you've added custom tables we don't know about.

────────────────────────────────────────────────────────────────────────
SAFETY
────────────────────────────────────────────────────────────────────────
   • These scripts do NOT change any data — they only fire the sync
     trigger via no-op UPDATEs.
   • Safe to re-run any number of times.
   • Wrapped in a transaction; if anything fails, nothing is committed.
