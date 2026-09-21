-- =====================================================================
-- V7: nightly housekeeping.
--
--   admin_settings  the few numbers the console can change without a
--                   redeploy. Kept as text so a new setting needs no
--                   migration; there are never many of them.
--
--   prune_runs      what each run did. Without a record, "did it run?"
--                   and "is it working?" are unanswerable, and a job
--                   nobody can see is a job nobody trusts.
--
-- The defaults below are the ones the 21-09-2026 volume scare called
-- for: 90 days of raw events (the shops' oldest events were 113 days
-- old, so 180 would have deleted nothing) and 30 days of notifications.
-- Nothing here touches projections, which is what the phones read.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.admin_settings (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  TEXT
);

INSERT INTO public.admin_settings (key, value, updated_by) VALUES
    ('prune.enabled',            'true', 'V7'),
    ('prune.events.days',        '90',   'V7'),
    ('prune.notifications.days', '30',   'V7'),
    ('prune.vacuum',             'true', 'V7')
ON CONFLICT (key) DO NOTHING;

CREATE TABLE IF NOT EXISTS public.prune_runs (
    id                    BIGSERIAL PRIMARY KEY,
    started_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at           TIMESTAMPTZ,
    triggered_by          TEXT NOT NULL,          -- 'schedule' or an admin's email
    dry_run               BOOLEAN NOT NULL DEFAULT FALSE,
    events_deleted        BIGINT NOT NULL DEFAULT 0,
    notifications_deleted BIGINT NOT NULL DEFAULT 0,
    bytes_before          BIGINT,
    bytes_after           BIGINT,
    shops                 INTEGER NOT NULL DEFAULT 0,
    note                  TEXT
);

CREATE INDEX IF NOT EXISTS ix_prune_runs_started ON public.prune_runs (started_at DESC);
