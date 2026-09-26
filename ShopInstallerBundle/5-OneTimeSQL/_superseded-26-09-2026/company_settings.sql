-- =====================================================================
--  COMPANY SETTINGS                                          (2026-09-14)
--
--  pgAdmin -> Query Tool on the "pawnbroking" database -> F5. Safe to run
--  again; it only creates what is missing.
--
--  One row per company and setting, for settings that are the company's
--  own (not per gold / silver). First use: Company Module -> Account
--  Settings -> Other Settings -> "EXPENSE / INCOME DETAILS THAT CAN BE
--  CHANGED" - whether the name, reason, "expenses for" and "expense or
--  asset" of a saved debit / credit may be changed. Until this table
--  exists they cannot be changed (as before), and a closed day's entries
--  can never be changed whatever the setting.
-- =====================================================================

CREATE TABLE IF NOT EXISTS company_settings (
    company_id    VARCHAR(100) NOT NULL,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value VARCHAR(500),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (company_id, setting_key)
);

SELECT 'company_settings' AS table_name,
       CASE WHEN to_regclass('public.company_settings') IS NOT NULL THEN 'ok' ELSE 'MISSING' END AS status;
