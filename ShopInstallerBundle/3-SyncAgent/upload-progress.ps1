# =====================================================================
#  UPLOAD PROGRESS  -  run on the shop PC, any time. Read-only.
#
#  Answers "has everything reached the cloud yet - and if not, how much
#  is left and how long will it take?" for the three things the sync
#  agent sends:
#     DATA     bills, customers, day accounts ...   (sync_outbox)
#     PHOTOS   bill and customer photos             (sync_image_uploads)
#     BACKUPS  files in the shop's backup folder    (sync_backup_uploads)
#
#  Photos and backups are counted with the agent's OWN rules, so "left"
#  is exactly what the agent will still upload - not stray files it
#  ignores, and not backups older than backup.retention.days.
#
#  Reads its settings from the agent's config; nothing to fill in:
#      C:\ProgramData\PawnBroking\sync.properties
#
#  USAGE (normal PowerShell, no admin needed):
#      powershell -ExecutionPolicy Bypass -File upload-progress.ps1
# =====================================================================

param([string]$Config = "C:\ProgramData\PawnBroking\sync.properties")

$CONFIG = $Config
$IMAGE_EXT = @('.png', '.jpg', '.jpeg', '.webp')

function Say([string]$text, [string]$color = "Gray") { Write-Host $text -ForegroundColor $color }
function Pct([double]$done, [double]$total) {
    if ($total -le 0) { return "100%" }
    return ("{0:N1}%" -f (100.0 * $done / $total))
}
function Eta([double]$left, [double]$perMin) {
    if ($left -le 0) { return "nothing left" }
    if ($perMin -le 0) { return "NOT MOVING right now" }
    $min = [math]::Ceiling($left / $perMin)
    if ($min -lt 60) { return "about $min min left" }
    $h = [math]::Floor($min / 60); $m = $min % 60
    if ($h -lt 48) { return "about $h h $m min left" }
    return ("about {0:N1} days left" -f ($min / 1440.0))
}
function Mb([double]$bytes) { return ("{0:N1} MB" -f ($bytes / 1MB)) }

Say ""
Say "=== Upload progress ===" "Cyan"
if (-not (Test-Path $CONFIG)) { Say "ERROR: config not found at $CONFIG - is the sync agent installed?" "Red"; exit 1 }

# ---- The agent's config ------------------------------------------------
$cfg = @{}
foreach ($line in Get-Content $CONFIG) {
    if ($line -match '^\s*([^#!=]+?)\s*=\s*(.*)$') { $cfg[$matches[1].Trim()] = $matches[2].Trim() }
}
$shopId = $cfg['shop.id']
$retentionDays = 30
if ($cfg['backup.retention.days']) { $retentionDays = [int]$cfg['backup.retention.days'] }
$healthPort = 17654
if ($cfg['health.port']) { $healthPort = [int]$cfg['health.port'] }
$imageRootOverride = $null
if ($cfg['image.root']) { $imageRootOverride = $cfg['image.root'].Replace('\\', '\') }

if ($cfg['db.url'] -match 'jdbc:postgresql://([^:/]+)(?::(\d+))?/([^?]+)') {
    $dbHost = $matches[1]
    $dbPort = "5432"; if ($matches[2]) { $dbPort = $matches[2] }
    $dbName = $matches[3]
} else { Say "ERROR: could not read db.url in $CONFIG" "Red"; exit 1 }

$psql = Get-ChildItem "C:\Program Files\PostgreSQL\*\bin\psql.exe", "C:\Program Files (x86)\PostgreSQL\*\bin\psql.exe" `
        -ErrorAction SilentlyContinue | Sort-Object FullName -Descending | Select-Object -First 1
if (-not $psql) { Say "ERROR: psql.exe not found under C:\Program Files\PostgreSQL" "Red"; exit 1 }

$env:PGPASSWORD = $cfg['db.password']
function Query([string]$sql) {
    $out = & $psql.FullName -h $dbHost -p $dbPort -U $cfg['db.user'] -d $dbName -X -t -A -F "|" -c $sql 2>&1
    if ($LASTEXITCODE -ne 0) { Say "ERROR from PostgreSQL: $out" "Red"; exit 1 }
    @($out | ForEach-Object { "$_" } | Where-Object { $_.Trim() -ne "" })
}
function Scalar([string]$sql) { $r = @(Query $sql); if ($r.Count) { return $r[0].Trim() } return "" }
function HasTable([string]$t) { return (Scalar "SELECT to_regclass('public.$t') IS NOT NULL") -eq 't' }

Say ("Shop: {0}    Database: {1}@{2}:{3}    {4}" -f $shopId, $dbName, $dbHost, $dbPort, (Get-Date -Format 'dd-MM-yyyy HH:mm'))

# ---- Is the agent running? ---------------------------------------------
$svc = Get-Service pawnbroking-sync -ErrorAction SilentlyContinue
$svcText = "NOT INSTALLED"; if ($svc) { $svcText = $svc.Status.ToString() }
$health = $null
try { $health = Invoke-RestMethod -Uri ("http://127.0.0.1:{0}/health" -f $healthPort) -TimeoutSec 3 } catch { }
$svcColor = "Green"; if ($svcText -ne "Running") { $svcColor = "Red" }
Say ("Sync agent : {0}" -f $svcText) $svcColor
if ($health -and $health.last_error) { Say ("  last error: {0}" -f $health.last_error) "Yellow" }

if (-not (HasTable 'sync_outbox')) {
    Say ""
    Say "The agent has not set up this database yet (no sync_outbox table)." "Red"
    Say "Check the service is Running and db.url ends in /$dbName, wait 30 s, run this again." "Red"
    exit 1
}

$summary = @()

# =======================================================================
#  DATA
# =======================================================================
Say ""
Say "DATA  (bills, customers, day accounts ...)" "Cyan"
$d = (Scalar @"
SELECT count(*) FILTER (WHERE sent_at IS NULL),
       count(*) FILTER (WHERE sent_at IS NOT NULL),
       count(*) FILTER (WHERE sent_at IS NULL AND attempts > 0),
       count(*) FILTER (WHERE sent_at > now() - interval '10 minutes'),
       COALESCE(to_char(max(sent_at), 'DD-MM-YYYY HH24:MI'), '-')
  FROM sync_outbox
"@).Split('|')
$waiting = [long]$d[0]; $sent = [long]$d[1]; $retrying = [long]$d[2]
$dataPerMin = [long]$d[3] / 10.0
$dlq = 0; if (HasTable 'sync_outbox_dlq') { $dlq = [long](Scalar "SELECT count(*) FROM sync_outbox_dlq") }
$marker = Scalar "SELECT COALESCE(obj_description('public.sync_outbox'::regclass, 'pg_class'), '')"

Say ("  Sent to the cloud : {0:N0}   (last one {1})" -f $sent, $d[4])
$c = "Green"; if ($waiting -gt 0) { $c = "Yellow" }
Say ("  Waiting to send   : {0:N0}   -> {1} done, {2}" -f $waiting, (Pct $sent ($sent + $waiting)), (Eta $waiting $dataPerMin)) $c
if ($waiting -gt 0) { Say ("  Sending at        : {0:N1} per minute (last 10 minutes)" -f $dataPerMin) }
if ($retrying -gt 0) {
    Say ("  Being retried     : {0:N0} - the cloud refused them for now. Reason:" -f $retrying) "Yellow"
    Query "SELECT DISTINCT left(last_error, 150) FROM sync_outbox WHERE sent_at IS NULL AND attempts > 0 LIMIT 3" |
        ForEach-Object { Say ("      {0}" -f $_) "Yellow" }
}
if ($dlq -gt 0) { Say ("  Dead-letter queue : {0:N0} refused for good (usually a wrong cloud.api_key) - see ROLLOUT_EXISTING_SHOPS.txt" -f $dlq) "Red" }
if ($marker) { Say ("  History send (F5) : {0}" -f $marker) }
else { Say "  History send (F5) : no record on this PC - existing bills reach the cloud only after the one-time history send" "Yellow" }
$summary += ("Data {0}" -f (Pct $sent ($sent + $waiting)))

# =======================================================================
#  PHOTOS
# =======================================================================
Say ""
Say "PHOTOS  (bill and customer photos)" "Cyan"
$roots = @()
$seen = @{}
foreach ($r in (Query "SELECT DISTINCT company_id, camera_temp_file_name FROM company_other_settings WHERE camera_temp_file_name IS NOT NULL AND trim(camera_temp_file_name) <> ''")) {
    $p = $r.Split('|', 2); $cid = $p[0]; $root = $p[1]
    if ($imageRootOverride) { $root = $imageRootOverride }
    if ($seen.ContainsKey($cid)) {
        if ($seen[$cid] -ne $root) {
            Say ("  NOTE: {0} has a second photo folder {1} - the agent walks only one folder per company ({2})" -f $cid, $root, $seen[$cid]) "Yellow"
        }
        continue
    }
    $seen[$cid] = $root
    $roots += , @($cid, $root)
}

$photoTotal = 0; $photoDone = 0; $photoLeft = 0; $photoLeftBytes = 0; $photoIgnored = 0; $photoMissing = 0
if ($roots.Count -eq 0) {
    Say "  No photo folder set (company_other_settings.camera_temp_file_name) - nothing to upload." "Yellow"
} else {
    $uploaded = @{}
    if (HasTable 'sync_image_uploads') {
        foreach ($p in (Query "SELECT abs_path FROM sync_image_uploads")) { $uploaded[$p.Trim().Replace('/', '\').ToLower()] = $true }
    }
    foreach ($pair in $roots) {
        $cid = $pair[0]; $root = $pair[1]
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            Say ("  {0}: folder {1} DOES NOT EXIST on this PC - its photos cannot upload" -f $cid, $root) "Red"
            $photoMissing++
            continue
        }
        $rootFull = [System.IO.Path]::GetFullPath($root).TrimEnd('\')
        Say ("  {0}: scanning {1} ..." -f $cid, $rootFull)
        foreach ($f in Get-ChildItem -LiteralPath $rootFull -Recurse -File -ErrorAction SilentlyContinue) {
            if ($IMAGE_EXT -notcontains $f.Extension.ToLower()) { continue }
            $segs = $f.FullName.Substring($rootFull.Length).TrimStart('\').Split('\')
            $n = $segs.Count
            # The agent's layouts: .../CUSTOMER(S)/<customerId>/<file>, or
            # <companyId>/<material>/<billNumber>/<file>; at most 5 levels deep.
            $isCustomer = $false
            for ($i = 0; $i -lt $n; $i++) {
                if ($segs[$i] -ieq 'CUSTOMERS' -or $segs[$i] -ieq 'CUSTOMER') { $isCustomer = ($i + 1 -lt $n); break }
            }
            $counted = ($n -le 5) -and ($isCustomer -or ($n -eq 4 -and $segs[0] -ieq $cid))
            if (-not $counted) { $photoIgnored++; continue }
            $photoTotal++
            if ($uploaded.ContainsKey($f.FullName.ToLower())) { $photoDone++ }
            else { $photoLeft++; $photoLeftBytes += $f.Length }
        }
    }
    $photoPerMin = 0.0
    if (HasTable 'sync_image_uploads') {
        $photoPerMin = [long](Scalar "SELECT count(*) FROM sync_image_uploads WHERE uploaded_at > now() - interval '10 minutes'") / 10.0
        $lastPhoto = Scalar "SELECT COALESCE(to_char(max(uploaded_at), 'DD-MM-YYYY HH24:MI'), '-') FROM sync_image_uploads"
    } else { $lastPhoto = '-' }
    Say ("  Photos on disk    : {0:N0}" -f $photoTotal)
    Say ("  Uploaded          : {0:N0}   (last one {1})" -f $photoDone, $lastPhoto)
    $c = "Green"; if ($photoLeft -gt 0) { $c = "Yellow" }
    Say ("  Still to upload   : {0:N0} ({1})   -> {2} done, {3}" -f $photoLeft, (Mb $photoLeftBytes), (Pct $photoDone $photoTotal), (Eta $photoLeft $photoPerMin)) $c
    if ($photoLeft -gt 0) { Say ("  Uploading at      : {0:N1} per minute (last 10 minutes; the agent's ceiling is about 54)" -f $photoPerMin) }
    if ($photoIgnored -gt 0) { Say ("  Not uploaded, by design: {0:N0} image files outside the folder layout the agent reads" -f $photoIgnored) }
    if ($photoMissing -gt 0) { $summary += ("Photos {0} - {1} FOLDER(S) MISSING" -f (Pct $photoDone $photoTotal), $photoMissing) }
    else { $summary += ("Photos {0}" -f (Pct $photoDone $photoTotal)) }
}

# =======================================================================
#  BACKUPS
# =======================================================================
Say ""
Say ("BACKUPS  (files in the backup folder; only the last {0} days are sent - backup.retention.days)" -f $retentionDays) "Cyan"
if ($retentionDays -le 0) { Say "  backup.retention.days = 0: every file is sent, whatever its age." }
$broots = @(Query "SELECT DISTINCT backup_file_path FROM company WHERE backup_file_path IS NOT NULL AND trim(backup_file_path) <> ''")
$bTotal = 0; $bDone = 0; $bLeft = 0; $bLeftBytes = 0; $bOld = 0; $bOldBytes = 0; $newestOnDisk = $null; $bMissing = 0
if ($broots.Count -eq 0) {
    Say "  No backup folder set (company.backup_file_path) - nothing to upload." "Yellow"
} else {
    $sentB = @{}
    if (HasTable 'sync_backup_uploads') {
        foreach ($r in (Query "SELECT abs_path, size_bytes, (extract(epoch from mtime) * 1000)::bigint FROM sync_backup_uploads")) {
            $p = $r.Split('|'); $sentB[$p[0].Trim().Replace('/', '\').ToLower()] = @([long]$p[1], [long]$p[2])
        }
    }
    $epoch = [datetime]'1970-01-01'
    $cutoffMs = ((Get-Date).ToUniversalTime() - $epoch).TotalMilliseconds - [double]$retentionDays * 86400000
    foreach ($root in $broots) {
        $root = $root.Trim()
        if (-not (Test-Path -LiteralPath $root -PathType Container)) {
            Say ("  Folder {0} DOES NOT EXIST on this PC - its backups cannot upload" -f $root) "Red"
            $bMissing++
            continue
        }
        Say ("  Scanning {0} ..." -f $root)
        foreach ($f in Get-ChildItem -LiteralPath $root -Recurse -File -ErrorAction SilentlyContinue) {
            $mtimeMs = ($f.LastWriteTimeUtc - $epoch).TotalMilliseconds
            if (-not $newestOnDisk -or $f.LastWriteTime -gt $newestOnDisk) { $newestOnDisk = $f.LastWriteTime }
            $s = $sentB[$f.FullName.ToLower()]
            if ($s -and $s[0] -eq $f.Length -and $mtimeMs -le $s[1] + 5000) { $bTotal++; $bDone++; continue }
            if ($retentionDays -gt 0 -and $mtimeMs -lt $cutoffMs) { $bOld++; $bOldBytes += $f.Length; continue }
            $bTotal++; $bLeft++; $bLeftBytes += $f.Length
        }
    }
    $lastBackup = '-'
    if (HasTable 'sync_backup_uploads') { $lastBackup = Scalar "SELECT COALESCE(to_char(max(uploaded_at), 'DD-MM-YYYY HH24:MI'), '-') FROM sync_backup_uploads" }
    $newestText = '-'; if ($newestOnDisk) { $newestText = $newestOnDisk.ToString('dd-MM-yyyy HH:mm') }
    Say ("  To be sent        : {0:N0} files   (newest backup on this PC: {1})" -f $bTotal, $newestText)
    Say ("  Uploaded          : {0:N0}   (last one {1})" -f $bDone, $lastBackup)
    $c = "Green"; if ($bLeft -gt 0) { $c = "Yellow" }
    Say ("  Still to upload   : {0:N0} ({1})   -> {2} done" -f $bLeft, (Mb $bLeftBytes), (Pct $bDone $bTotal)) $c
    if ($bOld -gt 0) {
        Say ("  Older than {0} days: {1:N0} files ({2}) - NOT sent, by setting. For all of them set backup.retention.days=0 and restart the service." -f $retentionDays, $bOld, (Mb $bOldBytes))
    }
    if ($bMissing -gt 0) { $summary += ("Backups {0} - {1} FOLDER(S) MISSING" -f (Pct $bDone $bTotal), $bMissing) }
    else { $summary += ("Backups {0}" -f (Pct $bDone $bTotal)) }
}

# ---- Recent upload problems in the agent's log -------------------------
$logs = @("C:\pawnbrokingSync\logs\pawnbroking-sync.out.log", "C:\pawnbrokingSync\logs\pawnbroking-sync.err.log",
          "C:\Program Files\PawnbrokingSync\pawnbroking-sync.out.log", "C:\Program Files\PawnbrokingSync\pawnbroking-sync.err.log") |
        Where-Object { Test-Path $_ }
$problems = @(foreach ($l in $logs) {
    Get-Content $l -Tail 400 -ErrorAction SilentlyContinue |
        Where-Object { $_ -match 'status=(401|403|413|429|502|503|507)|upload failed|transient cloud failure' }
})
if ($problems.Count) {
    Say ""
    Say "RECENT UPLOAD PROBLEMS (agent log):" "Yellow"
    $problems | Select-Object -Last 5 | ForEach-Object { Say ("  " + $_) "Yellow" }
    Say "  503 = nobody has signed in on the phone for this shop yet; 507 = the Magizhchi Share account is full;" "Gray"
    Say "  401 = wrong cloud.api_key; 502 on a big backup = old agent, run update-agent.bat." "Gray"
}

Say ""
Say ("SUMMARY: " + ($summary -join "   |   ")) "Cyan"
Say "Leave the PC on; run this again to watch the numbers move." "Gray"
Say ""
Remove-Item Env:PGPASSWORD -ErrorAction SilentlyContinue
