-- =====================================================================
--  SHOP PC STATUS  -  looks, changes nothing
--
--  What the setup exe runs in "Check only" and "Backups only" mode.
--  Every statement here reads. There is no INSERT, UPDATE, DELETE,
--  CREATE, ALTER or COMMENT in this file: a shop that is working
--  normally is left exactly as it was found.
--
--  The only thing it sets are session settings (set_config), which live
--  for the length of this one connection and touch no table and no file.
-- =====================================================================


-- Q1  Gather the numbers. Anything that may not exist on this PC - the
--     agent's tables, the folder columns - is read only if it is there.
DO $$
DECLARE n BIGINT; v_txt TEXT;
BEGIN
    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company_other_settings'
                  AND column_name = 'camera_temp_file_name') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT camera_temp_file_name, '   |   ')
                     FROM company_other_settings
                    WHERE camera_temp_file_name IS NOT NULL
                      AND trim(camera_temp_file_name) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('mb.photo_dirs', COALESCE(v_txt, '(none set)'), false);

    v_txt := NULL;
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'company'
                  AND column_name = 'backup_file_path') THEN
        EXECUTE $q$SELECT string_agg(DISTINCT backup_file_path, '   |   ')
                     FROM company
                    WHERE backup_file_path IS NOT NULL
                      AND trim(backup_file_path) <> ''$q$ INTO v_txt;
    END IF;
    PERFORM set_config('mb.backup_dirs', COALESCE(v_txt, '(none set)'), false);

    IF to_regclass('public.sync_outbox') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_outbox WHERE sent_at IS NULL' INTO n;
        PERFORM set_config('mb.pending', n::text, false);
        EXECUTE 'SELECT count(*) FROM sync_outbox WHERE sent_at IS NOT NULL' INTO n;
        PERFORM set_config('mb.sent', n::text, false);
        PERFORM set_config('mb.history',
            COALESCE(obj_description('public.sync_outbox'::regclass, 'pg_class'),
                     'NOT SENT YET on this PC - existing bills reach the cloud only after the one-time history send (Full setup)'),
            false);
    ELSE
        PERFORM set_config('mb.pending', '- (the agent has never run here)', false);
        PERFORM set_config('mb.sent',    '- (the agent has never run here)', false);
        PERFORM set_config('mb.history', 'NO - the agent has never set this database up', false);
    END IF;

    IF to_regclass('public.sync_outbox_dlq') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_outbox_dlq' INTO n;
        PERFORM set_config('mb.dlq', n::text, false);
    ELSE
        PERFORM set_config('mb.dlq', '-', false);
    END IF;

    IF to_regclass('public.sync_image_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_image_uploads' INTO n;
        PERFORM set_config('mb.images', n::text, false);
    ELSE
        PERFORM set_config('mb.images', '- (the agent has never run here)', false);
    END IF;
    IF to_regclass('public.sync_backup_uploads') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM sync_backup_uploads' INTO n;
        PERFORM set_config('mb.backups', n::text, false);
    ELSE
        PERFORM set_config('mb.backups', '- (the agent has never run here)', false);
    END IF;

    EXECUTE 'SELECT count(*) FROM company_billing' INTO n;
    PERFORM set_config('mb.rows_bills', n::text, false);
    IF to_regclass('public.repledge_billing') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM repledge_billing' INTO n;
        PERFORM set_config('mb.rows_repledge', n::text, false);
    ELSE
        PERFORM set_config('mb.rows_repledge', '(no table)', false);
    END IF;
    IF to_regclass('public.customer_details') IS NOT NULL THEN
        EXECUTE 'SELECT count(*) FROM customer_details' INTO n;
        PERFORM set_config('mb.rows_customers', n::text, false);
    ELSE
        PERFORM set_config('mb.rows_customers', '(no table)', false);
    END IF;
END $$;


-- Q2  THE REPORT. "MISSING" here is a statement of fact, not something
--     this run will go and fix - only Full setup does that.
SELECT step, item, status FROM (
    VALUES
    (1,  'Database',               current_database()::text),
    (2,  'SUSPENSE bill status',
         CASE WHEN EXISTS (SELECT 1 FROM pg_enum e JOIN pg_type ty ON ty.oid = e.enumtypid
                            WHERE ty.typname = 'company_bill_status' AND e.enumlabel = 'SUSPENSE')
              THEN 'ok' ELSE 'not there (Full setup adds it)' END),
    (3,  'Suspense table',
         CASE WHEN to_regclass('public.company_billing_suspense') IS NOT NULL
              THEN 'ok' ELSE 'not there (Full setup adds it)' END),
    (4,  'Re+ customer columns',
         CASE WHEN (SELECT count(*) FROM information_schema.columns
                     WHERE table_schema = 'public' AND table_name = 'customer_details'
                       AND column_name IN ('interest','document_charge','open_formula','close_formula')) = 4
              THEN 'ok' ELSE 'not there (Full setup adds them)' END),
    (5,  'Re+ dated pricing',
         CASE WHEN to_regclass('public.customer_pricing') IS NOT NULL
              THEN 'ok' ELSE 'not there (Full setup adds it)' END),
    (6,  'Notice mode column',
         CASE WHEN EXISTS (SELECT 1 FROM information_schema.columns
                            WHERE table_schema = 'public' AND table_name = 'company'
                              AND column_name = 'notice_one_per_bill')
              THEN 'ok' ELSE 'not there (Full setup adds it)' END),
    (7,  'repledge_billing key',
         CASE WHEN EXISTS (SELECT 1 FROM pg_index
                            WHERE indrelid = to_regclass('public.repledge_billing') AND indisprimary)
              THEN 'ok' ELSE 'none - repledges would collapse on the cloud (Full setup adds it)' END),
    (8,  'Capture function',
         CASE WHEN EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'sync_capture' AND prosrc LIKE '%indisprimary%')
              THEN 'current' WHEN EXISTS (SELECT 1 FROM pg_proc WHERE proname = 'sync_capture')
              THEN 'OLD - sends some rows under the wrong key' ELSE 'not installed' END),
    (9,  'History to the cloud',   current_setting('mb.history', true)),
    (10, 'Already sent',           current_setting('mb.sent', true)),
    (11, 'Waiting to send',        current_setting('mb.pending', true)),
    (12, 'Refused for good (dlq)', current_setting('mb.dlq', true)),
    (13, 'Photos uploaded so far', current_setting('mb.images', true)),
    (14, 'Backups uploaded so far', current_setting('mb.backups', true)),
    (15, 'Photo folder(s) - must exist on THIS PC',  current_setting('mb.photo_dirs', true)),
    (16, 'Backup folder(s) - must exist on THIS PC', current_setting('mb.backup_dirs', true)),
    (17, 'Desktop rows: company_billing',  current_setting('mb.rows_bills', true)),
    (18, 'Desktop rows: repledge_billing', current_setting('mb.rows_repledge', true)),
    (19, 'Desktop rows: customer_details', current_setting('mb.rows_customers', true))
) AS report(step, item, status)
ORDER BY step;
