-- =====================================================================
--  Send the four settings tables Bill Closing prices a bill with.
--
--  YOU PROBABLY DO NOT NEED THIS FILE.
--    PawnBrokingSyncSetup.exe does it at step S6b on every full setup,
--    once per shop. This is the by-hand version, for a PC you would
--    rather not run the whole setup on. Running both is harmless: each
--    guards itself, and the second finds nothing to do.
--
--  WHY
--    The phone can show what a bill was lent against, but not what it
--    would cost to close today, because the cloud has never been sent
--    the settings that price it:
--
--      company_formula                the close formula itself
--      company_reduce_months_or_days  the reduction and the minimum
--      company_month_setting          what a few leftover days count as
--      fine_charges                   the slabs past the accepted term
--
--    They were never in the installer's history list. Triggers do sit
--    on them, so a CHANGE would have synced - but settings tables do
--    not change, so nothing ever has.
--
--    The installer's history step is marked done on every shop already
--    running, and it will not run twice. So the rows have to be nudged
--    directly, which is all this does.
--
--  WHAT IT DOES
--    Four no-op updates. Each row is set to the value it already holds,
--    which fires the capture trigger and queues the row for the cloud.
--    No value anywhere changes. It is safe to run twice.
--
--    These are small tables - a few dozen rows between them - so this
--    adds almost nothing to the queue even mid-backlog.
--
--  RUN IT
--    psql -U postgres -d pawnbroking -f send_closing_settings_to_cloud.sql
--
--    Then leave the agent to it. Check it landed with, on the cloud:
--      SELECT table_name, count(*) FROM <shop>.projections
--       WHERE table_name IN ('company_formula',
--                            'company_reduce_months_or_days',
--                            'company_month_setting','fine_charges')
--       GROUP BY 1 ORDER BY 1;
-- =====================================================================

DO $$
DECLARE
    t     TEXT;
    n     BIGINT;
    total BIGINT := 0;
    tables TEXT[] := ARRAY['company_formula', 'company_reduce_months_or_days',
                           'company_month_setting', 'fine_charges'];
BEGIN
    IF to_regclass('public.sync_outbox') IS NULL THEN
        RAISE NOTICE 'No sync_outbox on this database - the agent has never run here. Nothing queued.';
        RETURN;
    END IF;

    FOREACH t IN ARRAY tables LOOP
        IF to_regclass('public.' || t) IS NULL THEN
            RAISE NOTICE '% is not on this shop - skipped.', t;
            CONTINUE;
        END IF;
        -- company_id is on all four and is never null, so writing it
        -- back to itself touches the row without altering it.
        EXECUTE format('UPDATE %I SET company_id = company_id', t);
        GET DIAGNOSTICS n = ROW_COUNT;
        total := total + n;
        RAISE NOTICE '% - % row(s) queued', t, n;
    END LOOP;

    RAISE NOTICE '-----------------------------------------------';
    RAISE NOTICE '% row(s) queued for the cloud. Leave the PC on.', total;
END $$;
