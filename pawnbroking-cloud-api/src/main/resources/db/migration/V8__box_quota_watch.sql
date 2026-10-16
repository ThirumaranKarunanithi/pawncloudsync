-- What each shop's cloud box is holding, and who has been told about it.
--
-- Before this table there was no answer anywhere to "is this shop about to
-- stop backing up?". The box knew its own number, the admin console counted
-- bytes without ever comparing them to the cap, and the shop found out when
-- its backups had already been missing for weeks — being told, wrongly, to
-- go and look at the backup job on its own PC.
--
-- One row per shop, refreshed by the nightly watch.
CREATE TABLE IF NOT EXISTS public.box_quota (
    shop_id      TEXT PRIMARY KEY REFERENCES public.tenants(shop_id),
    used_bytes   BIGINT      NOT NULL DEFAULT 0,
    limit_bytes  BIGINT      NOT NULL DEFAULT 0,
    checked_at   TIMESTAMPTZ,
    -- The band already warned about: 0, 80, 90 or 95. Kept so a shop sitting
    -- at 85% for a month is mailed ONCE, not thirty times — a warning that
    -- arrives every morning is one nobody reads by the end of the week, which
    -- would put us back exactly where we started.
    warned_band  INT         NOT NULL DEFAULT 0,
    warned_at    TIMESTAMPTZ,
    -- Last failure reading this shop's usage, so a box that cannot be reached
    -- is visible rather than looking like a shop that is comfortably empty.
    last_error   TEXT
);

-- "Which shops are in trouble" is the only question asked of this table.
CREATE INDEX IF NOT EXISTS ix_box_quota_pct
    ON public.box_quota ((CASE WHEN limit_bytes > 0
                               THEN used_bytes::float8 / limit_bytes
                               ELSE 0 END) DESC);
