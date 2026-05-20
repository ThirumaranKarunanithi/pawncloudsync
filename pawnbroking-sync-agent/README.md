# pawnbroking-sync-agent

Windows service that streams local PostgreSQL writes to the cloud API
in near real-time using `LISTEN/NOTIFY` + an outbox table.

## Build

```powershell
cd D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-sync-agent
mvn -DskipTests package
# produces: target\pawnbroking-sync-agent.jar (fat jar)
```

## Configure (per shop machine)

```powershell
New-Item -ItemType Directory -Force -Path "$env:PROGRAMDATA\PawnBroking" | Out-Null
Copy-Item .\sync.properties.sample "$env:PROGRAMDATA\PawnBroking\sync.properties"
notepad "$env:PROGRAMDATA\PawnBroking\sync.properties"
# set db.password, cloud.url, cloud.api_key, shop.id
```

## Install as Windows service

Download WinSW.NET4.exe from https://github.com/winsw/winsw/releases and place
it next to the jar, renamed `pawnbroking-sync.exe`. Then:

```powershell
cd D:\Pawnbroking\PawnBrokingMobApp\pawnbroking-sync-agent\target
Copy-Item ..\winsw\pawnbroking-sync.xml .
.\pawnbroking-sync.exe install
.\pawnbroking-sync.exe start
```

## Smoke test

```powershell
# 1) Insert a row directly in PG
psql -U postgres -d pawnbroking -c "INSERT INTO customer_master(name) VALUES ('SMOKE TEST');"

# 2) Within ~5s, health endpoint should show sent_total bumped
curl http://127.0.0.1:17654/health
```
