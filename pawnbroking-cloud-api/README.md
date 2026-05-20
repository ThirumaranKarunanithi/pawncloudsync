# pawnbroking-cloud-api

Spring Boot 3, Java 21. Multi-tenant via **schema-per-shop**
(`alwarpuram`, `annanagar`, ...). Receives sync events from each shop's
sync-agent, projects them into per-tenant tables, fans out FCM pushes.

## Add a new tenant (e.g. mylapore)

Either:
1. Add `mylapore` to env var `TENANTS=alwarpuram,annanagar,mylapore`, restart.
2. Or POST to admin endpoint (TODO) once you add auth for it.

On startup, `TenantBootstrap`:
- Creates schema `mylapore` if missing.
- Applies `db/tenant/tenant.sql` inside it.
- Inserts a row in `public.tenants`.
- Generates an API key in `public.shop_credentials` (prints it once in logs).
- Creates `admin/admin` user in `public.app_users` (CHANGE IT).

Read the printed API key from the log and paste into that shop's
`sync.properties` -> `cloud.api_key`.

## Endpoints

| Method | Path                                | Auth        | Purpose |
|--------|-------------------------------------|-------------|---------|
| POST   | `/v1/sync`                          | API key     | Batch ingest from sync-agent |
| POST   | `/v1/auth/mobile`                   | none        | Login from Android |
| POST   | `/v1/devices`                       | JWT         | Register FCM token |
| GET    | `/v1/data/dashboard`                | JWT         | Today's totals |
| GET    | `/v1/data/{table}?q=&limit=`        | JWT         | List projections |
| GET    | `/v1/data/{table}/{rowPk}`          | JWT         | One row |
| GET    | `/v1/data/notifications`            | JWT         | Notification inbox |

## Run locally

```powershell
docker compose up --build
# look in api logs for:
#   API KEY for shop 'alwarpuram' = ALWARPURAM_xxxxxxxxxxxx
# Swagger: http://localhost:8080/swagger
```

## Smoke test

```powershell
$KEY = "ALWARPURAM_xxxxxxxx"   # from logs
curl.exe -X POST http://localhost:8080/v1/sync `
  -H "Authorization: Bearer $KEY" `
  -H "Content-Type: application/json" `
  -d '{ "shop_id":"alwarpuram","events":[{"event_id":"11111111-1111-1111-1111-111111111111","table":"customer_master","op":"I","row_pk":"42","payload":{"name":"Test Customer","amount":1000},"created_at":"2026-05-19T10:00:00Z"}]}'

# login as admin and read dashboard
$TOK = (curl.exe -s -X POST http://localhost:8080/v1/auth/mobile -H "Content-Type: application/json" -d '{"shop_id":"alwarpuram","username":"admin","password":"admin"}' | ConvertFrom-Json).access_token
curl.exe -H "Authorization: Bearer $TOK" http://localhost:8080/v1/data/dashboard
```

## Deploy

- **Render / Railway / Fly.io:** push to GitHub, point service at this folder,
  set env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`, `TENANTS`,
  `GOOGLE_APPLICATION_CREDENTIALS` (path inside container), `FCM_ENABLED=true`.
- Mount the Firebase service-account JSON as a secret file.
