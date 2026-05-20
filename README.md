# PawnBrokingMobApp — Cloud Stack

Local-to-cloud sync + Android push notifications for the existing JavaFX
pawnbroking desktop app. Multi-tenant by **PostgreSQL schema-per-shop**
(`alwarpuram`, `annanagar`, …).

```
[Desktop @ Alwarpuram]                  [Desktop @ Annanagar]
   |  local PG                              |  local PG
   |  (triggers -> sync_outbox)             |  (triggers -> sync_outbox)
   v                                        v
[sync-agent svc]  ---HTTPS Bearer api_key--> [cloud-api on Railway]
                                              |
                                              +--> Cloud Postgres
                                              |     schema "alwarpuram"
                                              |     schema "annanagar"
                                              |
                                              +--> FCM push
                                                     |
                                                     v
                                          [Android app per shop user]
```

## Repository layout

| Folder                    | Role                                                          | Where it runs                               |
|---------------------------|---------------------------------------------------------------|---------------------------------------------|
| `pawnbroking-outbox/`     | SQL migration + apply script for the local PG                 | Each shop's local PostgreSQL                |
| `pawnbroking-sync-agent/` | Java 17 Windows service                                       | Each shop's Windows PC                      |
| `pawnbroking-cloud-api/`  | Spring Boot 3, schema-per-tenant, JWT, FCM                    | **Railway** (or Render / Fly.io) + Postgres |
| `pawnbroking-android/`    | Kotlin/Compose mobile app                                     | Phones (sideload APK or Play Store)         |

## Bring-up order

### 1. Deploy the cloud first

```powershell
cd D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-cloud-api
docker compose up --build
# in logs you'll see:
#   API KEY for shop 'alwarpuram' = ALWARPURAM_xxxxxxxxxxxxx
#   API KEY for shop 'annanagar'  = ANNANAGAR_yyyyyyyyyyyyy
# default mobile login for each shop: admin / admin  (CHANGE LATER)
```

For production, push this folder to GitHub and deploy on Railway:
- Add the Postgres plugin in the same Railway project.
- Set env vars: `DB_URL`, `DB_USER`, `DB_PASSWORD`, `JWT_SECRET`,
  `TENANTS=alwarpuram,annanagar`, `PORT=8080`.

### 2. Per shop machine — install outbox

```powershell
cd D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-outbox
$env:PGPASSWORD = "<local-pg-password>"
.\scripts\apply.ps1 -ShopId alwarpuram          # on the Alwarpuram machine
# on Annanagar machine instead:  .\apply.ps1 -ShopId annanagar
```

### 3. Per shop machine — install sync-agent

```powershell
cd D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-sync-agent
mvn -DskipTests package

New-Item -ItemType Directory -Force -Path "$env:PROGRAMDATA\PawnBroking"
Copy-Item .\sync.properties.sample "$env:PROGRAMDATA\PawnBroking\sync.properties"
notepad "$env:PROGRAMDATA\PawnBroking\sync.properties"
# fill: db.password, cloud.url (Railway URL),
#       cloud.api_key (from cloud bootstrap log), shop.id

# install as a Windows service via WinSW
cd .\target
Copy-Item ..\winsw\pawnbroking-sync.xml .
# download WinSW.NET461.exe, rename to pawnbroking-sync.exe, put here
.\pawnbroking-sync.exe install
.\pawnbroking-sync.exe start
```

### 4. Wire the existing JavaFX app to tag rows by shop

Already handled at the DB level — `apply.ps1` set a default `app.shop_id`
per database. Optionally, in each `DBOperation` class that calls
`DriverManager.getConnection(...)`, add:

```java
try (Statement st = conn.createStatement()) {
    st.execute("SET app.shop_id = 'alwarpuram'");
}
```

### 5. Install Android app

See `pawnbroking-android/README.md`. Each shop's users log in with
`shop_id = alwarpuram` (or whatever) + username/password.

## Verification path

1. Cloud-api running, logs show tenant bootstrap.
2. `psql` into cloud DB: `\dn` should list `public, alwarpuram, annanagar`.
3. Insert a customer in the desktop app at Alwarpuram.
4. `SELECT count(*) FROM sync_outbox WHERE sent_at IS NULL` on local PG → 0 within ~5s.
5. Cloud `SELECT count(*) FROM alwarpuram.events;` grew by 1.
6. Android app (logged in as Alwarpuram admin) buzzes with notification.
7. Tap → row detail screen renders.

## Hardening before production

- [ ] Replace `admin / admin` default users.
- [ ] DPAPI-encrypt `cloud.api_key` in `sync.properties`.
- [ ] Strong random `JWT_SECRET` from your cloud secret manager.
- [ ] HTTPS-only on the cloud (Railway provides this automatically).
- [ ] Monitor `lag_events` from `http://127.0.0.1:17654/health` on each shop machine.
- [ ] Document an API-key rotation procedure (see cloud-api README).
