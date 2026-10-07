-- =====================================================================
--  LOCAL CLOUD — 01  register the shop as a tenant
--
--  Runs against the LOCAL cloud database (pawnbroking_cloud), never
--  Railway. build-local-cloud.ps1 supplies the variables; to run it by
--  hand:
--
--    psql -d pawnbroking_cloud -v shop_id=iravathanallur ^
--         -v display_name="Iravathanallur Pawn Broking" ^
--         -v owner_email=rajeshwariiravathanallur@gmail.com ^
--         -v admin1=tirukaruna@gmail.com -v admin2=neelamanikandank@gmail.com ^
--         -f 01_provision_tenant.sql
--
--  Mirrors 5-OneTimeSQL\<shop>_cloud_provision.sql row for row, so the
--  local tenant looks exactly like the Railway one. Idempotent.
--  PSQL ONLY — run it through build-local-cloud.ps1, not pgAdmin. pgAdmin
--  cannot read \set / \gset / :'variable', and stops on the first line
--  with 'syntax error at or near "on"'. When that happens nothing in the
--  file has run: PostgreSQL rejects the whole batch before executing any
--  of it.
-- =====================================================================

\set ON_ERROR_STOP on

-- LOCAL MACHINE ONLY. Refuses any server that is not this machine, so the
-- file can never run against Railway — where the load step's TRUNCATE
-- would wipe the real tenant's event log. Nothing below runs if it fails.
DO $guard$
BEGIN
    IF COALESCE(host(inet_server_addr()), 'local') NOT IN ('local', '127.0.0.1', '::1') THEN
        RAISE EXCEPTION 'Refused: connected to % (database %). This is the LOCAL cloud mirror script and only runs against PostgreSQL on this machine. Nothing was changed.',
            host(inet_server_addr()), current_database();
    END IF;
END $guard$;

-- 1. The tenant row. schema_name = shop_id, as on Railway.
INSERT INTO public.tenants (shop_id, schema_name, display_name)
VALUES (:'shop_id', :'shop_id', :'display_name')
ON CONFLICT DO NOTHING;

UPDATE public.tenants
   SET primary_email = :'owner_email'
 WHERE shop_id = :'shop_id';


-- 2. Who may sign in — the same three addresses as the Railway tenant.
INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES (:'owner_email', :'shop_id', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES (:'admin1', :'shop_id', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;

INSERT INTO public.user_shop_access (email, shop_id, role)
VALUES (:'admin2', :'shop_id', 'OWNER')
ON CONFLICT (email, shop_id) DO NOTHING;


-- 3. A sync key, marked LOCAL so it can never be mistaken for the real
--    one. Only minted once — re-running does not pile keys up. It is
--    useful only if you point a locally running cloud-api at this
--    database; it is worthless against Railway.
INSERT INTO public.shop_credentials (api_key, shop_id, label)
SELECT 'mbk_local_' || replace(gen_random_uuid()::text, '-', ''),
       :'shop_id',
       'LOCAL MIRROR - not valid on Railway'
 WHERE NOT EXISTS (
        SELECT 1 FROM public.shop_credentials
         WHERE shop_id = :'shop_id' AND label LIKE 'LOCAL MIRROR%');


-- 4. The tenant's own schema. tenant.sql fills it next.
CREATE SCHEMA IF NOT EXISTS :"shop_id";


-- 5. Housekeeping for this mirror only — never exists on Railway.
--    Records what each load pulled, so 03_verify.sql can compare.
CREATE SCHEMA IF NOT EXISTS lc;

CREATE TABLE IF NOT EXISTS lc.load_log (
    shop_id          TEXT        NOT NULL,
    table_name       TEXT        NOT NULL,
    pk_used          TEXT        NOT NULL,
    shop_rows        BIGINT      NOT NULL,
    distinct_keys    BIGINT      NOT NULL,
    synthetic_keys   BIGINT      NOT NULL,
    loaded_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (shop_id, table_name)
);
