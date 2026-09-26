; =====================================================================
;  Pawnbroking Sync Agent — Windows installer (Inno Setup 6)
;
;  ONE exe for the whole shop PC, new shop or existing:
;    * Finds an install that is already here and fills the wizard in
;      from it — an existing shop is Next, Next, Install with nothing
;      typed
;    * Takes over a service installed in some other folder by hand
;    * Installs jar + WinSW + bundled JRE to  C:\pawnbrokingSync\
;    * Writes (merges) C:\ProgramData\PawnBroking\sync.properties,
;      keeping what the shop already had and correcting batch.size
;    * Registers + starts the Windows service "pawnbroking-sync"
;    * Then runs com.magizhchi.sync.Setup, which does the DATABASE:
;      sync tables and triggers, SUSPENSE, Re+ pricing, notice mode,
;      the repledge_billing key, the one-time history send (never
;      twice), and a check of the photo and backup folders
;    * Leaves "Sync progress report" and "Run setup again" shortcuts
;    * Uninstall stops & removes the service cleanly
;
;  Build via build-installer.bat — DO NOT compile this .iss directly,
;  the bat first jlinks the JRE and stages files into .\stage\.
; =====================================================================

#define AppName        "Pawnbroking Sync Agent"
#define AppVersion     "1.1.0"
#define AppPublisher   "Magizhchi"
#define AppUrl         "https://magizhchi.com"
#define ServiceId      "pawnbroking-sync"

[Setup]
AppId={{A5F1E6B0-9E32-4C0A-9E7D-6E7F8B9C0A11}}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
AppPublisherURL={#AppUrl}
AppSupportURL={#AppUrl}
DefaultDirName=C:\pawnbrokingSync
DefaultGroupName={#AppName}
DisableProgramGroupPage=yes
DisableDirPage=auto
OutputDir=dist
OutputBaseFilename=PawnBrokingSyncSetup
Compression=lzma2
SolidCompression=yes
PrivilegesRequired=admin
ArchitecturesInstallIn64BitMode=x64
UninstallDisplayName={#AppName}
UninstallDisplayIcon={app}\pawnbroking-sync.exe
WizardStyle=modern
ShowLanguageDialog=no
CloseApplications=no

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

; Every entry carries  Check: Installing  — in "Check only" and "Backups only"
; the wizard closes before this section runs, and if it ever did run, not one
; file would be copied. Two locks, because a shop that is working must come out
; of a look-only run exactly as it went in.
[Files]
; --- Sync agent binary + service wrapper ---
Source: "stage\pawnbroking-sync-agent.jar"; DestDir: "{app}"; Flags: ignoreversion; Check: Installing
Source: "stage\pawnbroking-sync.exe";       DestDir: "{app}"; Flags: ignoreversion; Check: Installing
Source: "stage\pawnbroking-sync.xml";       DestDir: "{app}"; Flags: ignoreversion; Check: Installing

; --- Bundled JRE (created by build-installer.bat via jlink) ---
Source: "stage\runtime\*"; DestDir: "{app}\runtime"; Flags: ignoreversion recursesubdirs createallsubdirs; Check: Installing

; --- Helper scripts ---
Source: "uninstall-service.bat"; DestDir: "{app}"; Flags: ignoreversion; Check: Installing
Source: "update-agent.bat";      DestDir: "{app}"; Flags: ignoreversion; Check: Installing
Source: "run-setup.bat";         DestDir: "{app}"; Flags: ignoreversion; Check: Installing
Source: "upload-progress.ps1";   DestDir: "{app}"; Flags: ignoreversion; Check: Installing

; --- Config template (installed for reference — the real file is written
;     and merged by the setup tool into ProgramData) ---
Source: "sync.properties.template"; DestDir: "{app}"; Flags: ignoreversion; Check: Installing

; --- A second copy of the jar that is NEVER installed. "Check only" and
;     "Backups only" run it from {tmp} against the shop's existing Java, so
;     those two modes put nothing on the machine at all. ---
Source: "stage\pawnbroking-sync-agent.jar"; Flags: dontcopy

[Dirs]
; Log dir the WinSW config points to; give service full write.
Name: "{app}\logs"; Permissions: users-modify
; ProgramData subdir for the runtime config file.
Name: "{commonappdata}\PawnBroking"; Permissions: users-modify

[Icons]
Name: "{group}\Sync progress report";      Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -NoExit -File ""{app}\upload-progress.ps1"""; \
    WorkingDir: "{app}"; Comment: "How much data, how many photos and backups have reached the cloud"; \
    Check: Installing
Name: "{group}\Check only (changes nothing)"; Filename: "{app}\run-setup.bat"; \
    Parameters: "--mode check"; WorkingDir: "{app}"; Check: Installing
Name: "{group}\Check the backup files";    Filename: "{app}\run-setup.bat"; \
    Parameters: "--mode backups"; WorkingDir: "{app}"; Check: Installing
Name: "{group}\Run setup again";           Filename: "{app}\run-setup.bat"; WorkingDir: "{app}"; Check: Installing
Name: "{group}\Sync Agent — Health Check"; Filename: "http://127.0.0.1:17654/health"; Check: Installing
Name: "{group}\Sync Agent — Logs Folder";  Filename: "{app}\logs"; Check: Installing
Name: "{group}\Sync Agent — Config File";  Filename: "{commonappdata}\PawnBroking\sync.properties"; Check: Installing
Name: "{group}\Uninstall Sync Agent";      Filename: "{uninstallexe}"; Check: Installing
Name: "{commondesktop}\Sync progress report"; Filename: "powershell.exe"; \
    Parameters: "-NoProfile -ExecutionPolicy Bypass -NoExit -File ""{app}\upload-progress.ps1"""; \
    WorkingDir: "{app}"; Tasks: desktopicon; Check: Installing

[Tasks]
Name: "desktopicon"; Description: "Put a ""Sync progress report"" icon on the desktop"; GroupDescription: "Shortcuts:"

[UninstallRun]
; Stop + uninstall the Windows service BEFORE the {app} folder is removed.
Filename: "{app}\uninstall-service.bat"; Flags: runhidden waituntilterminated; RunOnceId: "SyncSvcUninstall"

[Code]
// =====================================================================
//  Custom wizard pages: shop identity + DB credentials.
//
//  Everything is pre-filled from an install that is already on this PC,
//  so re-running this on an existing shop is Next, Next, Install.
//  The values are handed to com.magizhchi.sync.Setup at ssPostInstall,
//  which merges them into ProgramData\PawnBroking\sync.properties and
//  then does the whole database side.
// =====================================================================

var
  ModePage: TInputOptionWizardPage;   // only shown when this PC already has a shop on it
  BkPage:   TInputOptionWizardPage;   // the two backup repairs, both off by default
  ShopPage: TInputQueryWizardPage;
  DbPage:   TInputQueryWizardPage;
  ExistingShopId: String;      // '' when this is a new shop
  ExistingAgentDir: String;    // a service already registered from elsewhere
  SilentExit: Boolean;         // closing after a look-only run is not a cancel

const
  DEFAULT_CLOUD_URL = 'https://devpawn.magizhchi.academy';
  CONFIG_REL        = '\PawnBroking\sync.properties';
  MODE_CHECK   = 0;
  MODE_BACKUPS = 1;
  MODE_FULL    = 2;

// ---- helpers ----

function TrimStr(const S: String): String;
begin
  Result := Trim(S);
end;

function IsLowerAlphaNum(const S: String): Boolean;
var
  I: Integer;
  C: Char;
begin
  Result := False;
  if Length(S) = 0 then Exit;
  for I := 1 to Length(S) do begin
    C := S[I];
    if not (((C >= 'a') and (C <= 'z')) or ((C >= '0') and (C <= '9'))) then Exit;
  end;
  Result := True;
end;

// Run a shell command capturing exit code; nothing printed to the wizard.
function RunHidden(const Cmd, Params: String): Integer;
var
  ResultCode: Integer;
begin
  if Exec(Cmd, Params, '', SW_HIDE, ewWaitUntilTerminated, ResultCode) then
    Result := ResultCode
  else
    Result := -1;
end;

// Run a command in a VISIBLE console so the shop can watch it work.
function RunVisible(const Cmd, Params, WorkDir: String): Integer;
var
  ResultCode: Integer;
begin
  if Exec(Cmd, Params, WorkDir, SW_SHOW, ewWaitUntilTerminated, ResultCode) then
    Result := ResultCode
  else
    Result := -1;
end;

function ConfigPath(): String;
begin
  Result := ExpandConstant('{commonappdata}') + CONFIG_REL;
end;

// One value out of a java .properties file. '' when absent.
// Undoes the only escape we write: a doubled backslash in a path.
function ReadProp(const Path, Key: String): String;
var
  Lines: TArrayOfString;
  I, P: Integer;
  L, K: String;
begin
  Result := '';
  if not FileExists(Path) then Exit;
  if not LoadStringsFromFile(Path, Lines) then Exit;
  for I := 0 to GetArrayLength(Lines) - 1 do begin
    L := Trim(Lines[I]);
    if (L = '') or (L[1] = '#') or (L[1] = '!') then Continue;
    P := Pos('=', L);
    if P <= 1 then Continue;
    K := Trim(Copy(L, 1, P - 1));
    if CompareText(K, Key) = 0 then begin
      Result := Trim(Copy(L, P + 1, Length(L) - P));
      StringChangeEx(Result, '\\', '\', True);
      Exit;
    end;
  end;
end;

// Where an already-registered service keeps its files, '' if none.
// WinSW's ImagePath is the exe itself, sometimes quoted.
function ExistingServiceDir(): String;
var
  S: String;
  P: Integer;
begin
  Result := '';
  if not RegQueryStringValue(HKEY_LOCAL_MACHINE,
       'SYSTEM\CurrentControlSet\Services\{#ServiceId}', 'ImagePath', S) then Exit;
  S := Trim(S);
  StringChangeEx(S, '"', '', True);
  P := Length(S);
  while (P > 0) and (S[P] <> '\') do P := P - 1;
  if P > 1 then Result := Copy(S, 1, P - 1);
end;

function JavaExe(): String;
begin
  Result := ExpandConstant('{app}\runtime\bin\java.exe');
end;

// Java for a look-only run, BEFORE anything is installed: the Java that came
// with the agent already on this PC, else one on the PATH.
function ExistingJavaExe(): String;
var
  Candidate: String;
begin
  Result := '';
  if ExistingAgentDir <> '' then begin
    Candidate := ExistingAgentDir + '\runtime\bin\java.exe';
    if FileExists(Candidate) then begin Result := Candidate; Exit; end;
  end;
  Candidate := 'C:\pawnbrokingSync\runtime\bin\java.exe';
  if FileExists(Candidate) then begin Result := Candidate; Exit; end;
  Candidate := ExpandConstant('{app}\runtime\bin\java.exe');
  if FileExists(Candidate) then begin Result := Candidate; Exit; end;
  // Last resort: whatever "java" the machine has. Exec finds it on the PATH.
  Result := 'java.exe';
end;

// True when this run is only allowed to look (and, in backups mode, to touch
// the backup files). Nothing is installed and no file is copied.
function IsLookOnlyRun(): Boolean;
begin
  Result := (ModePage <> nil) and (not ModePage.Values[MODE_FULL]);
end;

// Used as  Check: Installing  on every [Files] and [Icons] entry.
function Installing(): Boolean;
begin
  Result := not IsLookOnlyRun();
end;

function SelectedMode(): String;
begin
  if ModePage = nil then Result := 'full'
  else if ModePage.Values[MODE_CHECK] then Result := 'check'
  else if ModePage.Values[MODE_BACKUPS] then Result := 'backups'
  else Result := 'full';
end;

// Lines the setup tool marked as needing a person — missing photo folder,
// refused key, a repledge id that stops the history going.
function AttentionLines(const ReportPath: String): String;
var
  Lines: TArrayOfString;
  I: Integer;
  L: String;
begin
  Result := '';
  if not FileExists(ReportPath) then Exit;
  if not LoadStringsFromFile(ReportPath, Lines) then Exit;
  for I := 0 to GetArrayLength(Lines) - 1 do begin
    L := Trim(Lines[I]);
    if Copy(L, 1, 2) = '!!' then
      Result := Result + '  - ' + Copy(L, 4, Length(L) - 3) + #13#10;
  end;
end;

// Escape a value for a Windows command line argument.
function CmdArg(const S: String): String;
var
  T: String;
begin
  T := S;
  StringChangeEx(T, '"', '\"', True);
  Result := '"' + T + '"';
end;

// ---- wizard construction ----

procedure InitializeWizard;
var
  Cfg, SavedShop, SavedKey, SavedUrl, SavedUser, SavedPass: String;
begin
  SilentExit := False;
  Cfg := ConfigPath();
  SavedShop := ReadProp(Cfg, 'shop.id');
  SavedKey  := ReadProp(Cfg, 'cloud.api_key');
  SavedUrl  := ReadProp(Cfg, 'cloud.url');
  SavedUser := ReadProp(Cfg, 'db.user');
  SavedPass := ReadProp(Cfg, 'db.password');
  ExistingShopId   := SavedShop;
  ExistingAgentDir := ExistingServiceDir();

  // A PC that already has a shop on it gets the choice first, and the safe
  // option is the one already selected. A brand-new PC never sees this page:
  // there, the only sensible thing is the full setup.
  if (ExistingShopId <> '') or (ExistingAgentDir <> '') then begin
    ModePage := CreateInputOptionPage(wpWelcome,
      'This PC already has a shop on it',
      'Shop "' + ExistingShopId + '" is already set up here. Choose how far this run may go.',
      'The first choice changes NOTHING - it looks and prints a report. Pick it if the shop ' +
      'is working and you only want to see where things stand.',
      True, False);
    ModePage.Add('Check only - look, change nothing on this PC');
    ModePage.Add('Backups only - check the backup files and fix just those');
    ModePage.Add('Full setup - install/repair everything (new shop, or a repair)');
    ModePage.SelectedValueIndex := MODE_CHECK;

    BkPage := CreateInputOptionPage(ModePage.ID,
      'Backup files',
      'Both boxes are OFF. With neither ticked this run only LOOKS at the backups.',
      'Tick one only if the report you have already seen calls for it. Nothing outside the ' +
      'backup files is touched either way - not the bills, not the photos, not the history.',
      False, False);
    BkPage.Add('Also upload backups older than the retention window (sets backup.retention.days = 0)');
    BkPage.Add('Upload every backup again (forgets what this PC thinks it has already sent)');
  end;

  ShopPage := CreateInputQueryPage(wpSelectDir,
    'Shop identity',
    'Enter the per-shop values obtained from the cloud admin.',
    'On a shop that already has the agent these are filled in from this PC — just press Next. ' +
    'The Cloud API key is the mbk_ key from R5 of the shop''s cloud provisioning file.');
  ShopPage.Add('Shop ID (lowercase, letters/digits only — e.g. dhineshsuganya):', False);
  ShopPage.Add('Cloud API key (starts with mbk_):', False);
  ShopPage.Add('Cloud URL:', False);
  ShopPage.Values[0] := SavedShop;
  ShopPage.Values[1] := SavedKey;
  if SavedUrl <> '' then ShopPage.Values[2] := SavedUrl
  else ShopPage.Values[2] := DEFAULT_CLOUD_URL;

  DbPage := CreateInputQueryPage(ShopPage.ID,
    'Local PostgreSQL',
    'Credentials for the local ''pawnbroking'' database on this machine.',
    'The Sync Agent connects to the local DB as this user. Any Postgres role with ' +
    'read/write on the pawnbroking DB will do — usually the postgres superuser.');
  DbPage.Add('DB user:', False);
  DbPage.Add('DB password:', True);
  if SavedUser <> '' then DbPage.Values[0] := SavedUser else DbPage.Values[0] := 'postgres';
  DbPage.Values[1] := SavedPass;
end;

// Tell the operator what this run is going to be, before they commit.
function UpdateReadyMemo(const Space, NewLine, MemoUserInfoInfo, MemoDirInfo,
  MemoTypeInfo, MemoComponentsInfo, MemoGroupInfo, MemoTasksInfo: String): String;
var
  S: String;
begin
  if ExistingShopId <> '' then
    S := 'Existing shop on this PC: ' + ExistingShopId + NewLine +
         Space + 'The agent is updated and the database is repaired.' + NewLine +
         Space + 'The history is NOT sent again — it only ever goes once.' + NewLine + NewLine
  else
    S := 'New shop: ' + TrimStr(ShopPage.Values[0]) + NewLine +
         Space + 'The agent is installed and the shop''s history is sent to the cloud' + NewLine +
         Space + 'once, which can take a few minutes. Do this when nobody is billing.' + NewLine + NewLine;

  if (ExistingAgentDir <> '') and (CompareText(ExistingAgentDir, ExpandConstant('{app}')) <> 0) then
    S := S + 'The service registered from ' + ExistingAgentDir + NewLine +
         Space + 'will be removed and re-registered from the new folder.' + NewLine + NewLine;

  S := S + MemoDirInfo + NewLine + NewLine + MemoTasksInfo;
  Result := S;
end;

// The backup options belong to backups mode only; the install pages belong to
// the full setup only.
function ShouldSkipPage(PageID: Integer): Boolean;
begin
  Result := False;
  if (BkPage <> nil) and (PageID = BkPage.ID) then
    Result := (SelectedMode() <> 'backups')
  else if IsLookOnlyRun() and ((PageID = wpSelectDir) or (PageID = wpReady) or
                               (PageID = ShopPage.ID) or (PageID = DbPage.ID)) then
    Result := True;
end;

// Closing the wizard after a look-only run is the normal end of that run, not
// a cancelled install, so it must not ask "Exit Setup?".
procedure CancelButtonClick(CurPageID: Integer; var Cancel, Confirm: Boolean);
begin
  if SilentExit then Confirm := False;
end;

// A look-only run: the jar goes to a temp folder, is run against the Java the
// shop already has, and nothing is installed. The report is left in the user's
// TEMP folder, not in the shop's install folder.
function RunLookOnly(const Mode: String): Boolean;
var
  Args, Jar, ReportPath, Attention, Msg, Java: String;
  Code: Integer;
  Restart: Boolean;
begin
  Result := False;
  if not FileExists(ConfigPath()) then begin
    MsgBox('There is no ' + ConfigPath() + ' on this PC, so there is nothing to check yet.'#13#10#13#10 +
           'Choose "Full setup" to set this shop up.', mbError, MB_OK);
    Exit;
  end;

  ExtractTemporaryFile('pawnbroking-sync-agent.jar');
  Jar := ExpandConstant('{tmp}\pawnbroking-sync-agent.jar');
  ReportPath := ExpandConstant('{%TEMP|C:\Windows\Temp}') + '\pawnbroking-' + Mode + '-report.txt';
  Java := ExistingJavaExe();

  Args := '-cp ' + CmdArg(Jar) + ' com.magizhchi.sync.Setup --run' +
          ' --mode '   + Mode +
          ' --config ' + CmdArg(ConfigPath()) +
          ' --report ' + CmdArg(ReportPath);

  Restart := False;
  if Mode = 'backups' then begin
    if BkPage.Values[0] then begin
      Args := Args + ' --backup-retention 0';
      Restart := True;
    end;
    if BkPage.Values[1] then Args := Args + ' --requeue-backups';
  end;

  Code := RunVisible(Java, Args, ExpandConstant('{tmp}'));
  if Code <> 0 then begin
    MsgBox('The ' + Mode + ' run did not finish (exit ' + IntToStr(Code) + ').'#13#10#13#10 +
           'Nothing on this PC was changed. The report, as far as it got, is at:'#13#10 +
           ReportPath, mbError, MB_OK);
  end else begin
    Attention := AttentionLines(ReportPath);
    if Mode = 'check' then
      Msg := 'Checked. NOTHING on this PC was changed.' + #13#10#13#10
    else
      Msg := 'Backups checked.' + #13#10 +
             'Only the backup files could have changed - the bills, the photos, the history ' +
             'and every other setting were left alone.' + #13#10#13#10;
    if Attention <> '' then
      Msg := Msg + 'These need a person:' + #13#10 + Attention + #13#10
    else
      Msg := Msg + 'Nothing is outstanding.' + #13#10#13#10;
    Msg := Msg + 'Full report: ' + ReportPath;

    if Restart and (ExistingAgentDir <> '') and FileExists(ExistingAgentDir + '\pawnbroking-sync.exe') then begin
      RunHidden(ExistingAgentDir + '\pawnbroking-sync.exe', 'restart');
      Msg := Msg + #13#10#13#10 + 'The agent service was restarted so the new retention setting counts.';
    end;

    MsgBox(Msg, mbInformation, MB_OK);
  end;

  if FileExists(ReportPath) then
    ShellExec('open', 'notepad.exe', '"' + ReportPath + '"', '', SW_SHOW, ewNoWait, Code);
  Result := True;
end;

// Validate wizard input per page; block Next on bad values.
function NextButtonClick(CurPageID: Integer): Boolean;
var
  ShopId, ApiKey, CloudUrl, DbPass: String;
begin
  Result := True;

  if (ModePage <> nil) and (CurPageID = ModePage.ID) then begin
    if SelectedMode() = 'check' then begin
      RunLookOnly('check');
      SilentExit := True;       // the run IS the whole job; install nothing
      WizardForm.Close;
      Result := False;
    end;
    // 'backups' falls through to its options page; 'full' to the normal pages.
    Exit;
  end;

  if (BkPage <> nil) and (CurPageID = BkPage.ID) then begin
    if BkPage.Values[1] then
      if MsgBox('"Upload every backup again" makes this PC send every backup file in the window ' +
                'to the cloud once more.'#13#10#13#10 +
                'It replaces them there rather than duplicating, but it does use the shop''s ' +
                'internet and Share storage. Only do this when the cloud is actually missing a ' +
                'file this PC thinks it sent.'#13#10#13#10'Go ahead?', mbConfirmation, MB_YESNO) <> IDYES then begin
        Result := False;
        Exit;
      end;
    RunLookOnly('backups');
    SilentExit := True;
    WizardForm.Close;
    Result := False;
    Exit;
  end;

  if CurPageID = ShopPage.ID then begin
    ShopId   := TrimStr(ShopPage.Values[0]);
    ApiKey   := TrimStr(ShopPage.Values[1]);
    CloudUrl := TrimStr(ShopPage.Values[2]);

    ShopId := Lowercase(ShopId);
    ShopPage.Values[0] := ShopId;

    if not IsLowerAlphaNum(ShopId) then begin
      MsgBox('Shop ID must be lowercase letters and digits only — e.g. dhineshsuganya.',
             mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if (ExistingShopId <> '') and (CompareText(ExistingShopId, ShopId) <> 0) then begin
      if MsgBox('This PC is already set up as "' + ExistingShopId + '", and you have typed "' +
                ShopId + '".'#13#10#13#10 +
                'Changing it points this shop''s data at a different tenant on the cloud, ' +
                'which is almost never right.'#13#10#13#10 +
                'Keep "' + ShopId + '" anyway?', mbConfirmation, MB_YESNO) <> IDYES then begin
        ShopPage.Values[0] := ExistingShopId;
        Result := False;
        Exit;
      end;
    end;
    if Pos('mbk_', ApiKey) <> 1 then begin
      MsgBox('Cloud API key must start with "mbk_". Get this from the cloud admin ' +
             'after provisioning the tenant (R5 of the shop''s cloud file).', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if (Pos('http://', CloudUrl) <> 1) and (Pos('https://', CloudUrl) <> 1) then begin
      MsgBox('Cloud URL must start with http:// or https://.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
  end
  else if CurPageID = DbPage.ID then begin
    DbPass := DbPage.Values[1];
    if Length(TrimStr(DbPage.Values[0])) = 0 then begin
      MsgBox('DB user cannot be empty.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
    if Length(DbPass) = 0 then begin
      MsgBox('DB password cannot be empty.', mbError, MB_OK);
      Result := False;
      Exit;
    end;
  end;
end;

// ---- post-install: config, service, then the whole database side ----

// Hand the wizard's values to the setup tool, which MERGES them into
// sync.properties instead of overwriting a file the shop may have tuned.
function WriteSyncProperties(): Integer;
var
  Args, AnswersPath: String;
  Answers: TArrayOfString;
begin
  // The answers go in a file, not on the command line: Inno writes every Exec
  // parameter into its own setup log, and the shop's database password has no
  // business being there. The setup tool deletes the file as it reads it, and
  // we delete it again below in case it could not.
  AnswersPath := ExpandConstant('{app}\setup-answers.tmp');
  SetArrayLength(Answers, 5);
  Answers[0] := 'shop-id=' + TrimStr(ShopPage.Values[0]);
  Answers[1] := 'api-key=' + TrimStr(ShopPage.Values[1]);
  Answers[2] := 'cloud-url=' + TrimStr(ShopPage.Values[2]);
  Answers[3] := 'db-user=' + TrimStr(DbPage.Values[0]);
  // NB: the DB password is NOT trimmed — trailing spaces in a password are legal.
  Answers[4] := 'db-password=' + DbPage.Values[1];
  if not SaveStringsToUTF8File(AnswersPath, Answers, False) then begin
    Log('could not write ' + AnswersPath);
    Result := -1;
    Exit;
  end;

  Args := '-cp ' + CmdArg(ExpandConstant('{app}\pawnbroking-sync-agent.jar')) +
          ' com.magizhchi.sync.Setup --write-config' +
          ' --config '  + CmdArg(ConfigPath()) +
          ' --answers ' + CmdArg(AnswersPath);
  Result := RunHidden(JavaExe(), Args);
  DeleteFile(AnswersPath);
end;

// If a previous install left the service running, stop+uninstall so we can
// safely overwrite the jar — including one registered from another folder.
procedure StopAndRemoveExistingService();
var
  Bat, OldExe: String;
begin
  if (ExistingAgentDir <> '') and (CompareText(ExistingAgentDir, ExpandConstant('{app}')) <> 0) then begin
    OldExe := ExistingAgentDir + '\pawnbroking-sync.exe';
    if FileExists(OldExe) then begin
      Log('Removing pawnbroking-sync registered from ' + ExistingAgentDir);
      RunHidden(OldExe, 'stop');
      RunHidden(OldExe, 'uninstall');
    end;
  end;
  Bat := ExpandConstant('{app}\uninstall-service.bat');
  if FileExists(Bat) then begin
    Log('Removing existing pawnbroking-sync service before file copy...');
    RunHidden(ExpandConstant('{cmd}'), '/C "' + Bat + '"');
  end;
end;

procedure InstallService();
var
  Code: Integer;
begin
  Code := RunHidden(ExpandConstant('{app}\pawnbroking-sync.exe'), 'install');
  // NB: keep the argument list off the start of a line — Inno reads a leading
  // '[' as a section tag and the compile dies with "Invalid section tag".
  if Code <> 0 then
    MsgBox('Service install returned ' + IntToStr(Code) + '. Check ' +
           ExpandConstant('{app}') + '\logs\pawnbroking-sync.wrapper.log', mbError, MB_OK);
end;

procedure StartService();
var
  Code: Integer;
begin
  Code := RunHidden(ExpandConstant('{app}\pawnbroking-sync.exe'), 'start');
  if Code <> 0 then
    MsgBox('Service failed to start (exit ' + IntToStr(Code) + '). Open ' +
           ExpandConstant('{app}') + '\logs\ and check .wrapper.log and .err.log for the cause.',
           mbError, MB_OK);
end;

// The shop PC half: sync tables + triggers, SUSPENSE, Re+ pricing, notice
// mode, repledge key, the one-time history send, photo and backup folders.
// Runs in a visible console — a big shop's history takes a few minutes and
// the operator should see it working.
function RunShopSetup(): Integer;
var
  Args: String;
begin
  Args := '-cp ' + CmdArg(ExpandConstant('{app}\pawnbroking-sync-agent.jar')) +
          ' com.magizhchi.sync.Setup --run' +
          ' --config ' + CmdArg(ConfigPath()) +
          ' --report ' + CmdArg(ExpandConstant('{app}\logs\setup-report.txt'));
  Result := RunVisible(JavaExe(), Args, ExpandConstant('{app}'));
end;

// Best-effort health probe. Returns True on HTTP 200. We DON'T block install
// on failure — the service may still be starting when this runs.
function ProbeHealth(): Boolean;
var
  Args: String;
begin
  Args := '-NoProfile -ExecutionPolicy Bypass -Command "try { $r = Invoke-WebRequest -UseBasicParsing ' +
          '-Uri http://127.0.0.1:17654/health -TimeoutSec 5; if ($r.StatusCode -eq 200) { exit 0 } ' +
          'else { exit 2 } } catch { exit 1 }"';
  Result := (RunHidden('powershell.exe', Args) = 0);
end;

procedure CurStepChanged(CurStep: TSetupStep);
var
  HealthOk: Boolean;
  SetupCode: Integer;
  Msg, ReportPath, Attention: String;
begin
  // A look-only run has already done its job on the mode page. It never takes
  // the service down, never swaps a file and never writes the config.
  if IsLookOnlyRun() then Exit;

  if CurStep = ssInstall then begin
    // Called after the user hits "Install" but before any file is copied.
    // Take the service down cleanly so we don't fight file locks.
    StopAndRemoveExistingService();
  end;

  if CurStep = ssPostInstall then begin
    ReportPath := ExpandConstant('{app}\logs\setup-report.txt');

    // Order matters — the config must exist before the service starts.
    if WriteSyncProperties() <> 0 then begin
      MsgBox('Could not write ' + ConfigPath() + '.'#13#10#13#10 +
             'The agent cannot run without it. Nothing else was changed.', mbError, MB_OK);
      Exit;
    end;

    InstallService();
    StartService();

    // Everything the shop PC needs, in one run. It does its own waiting for
    // PostgreSQL, so a DB that is still starting is not a failure.
    SetupCode := RunShopSetup();

    Sleep(3000);
    HealthOk := ProbeHealth();

    Msg := 'Sync Agent installed to ' + ExpandConstant('{app}') + #13#10 +
           'Config file:  ' + ConfigPath() + #13#10 +
           'Service:      pawnbroking-sync (autostart)' + #13#10 +
           'Report:       ' + ReportPath + #13#10#13#10;

    if SetupCode <> 0 then
      Msg := Msg + 'THE DATABASE SETUP DID NOT FINISH (exit ' + IntToStr(SetupCode) + ').' + #13#10 +
                   'The agent is installed and keeps trying. Open the report above for the reason, ' +
                   'fix it, then use "Run setup again" in the Start menu.' + #13#10#13#10
    else begin
      Attention := AttentionLines(ReportPath);
      if Attention <> '' then
        Msg := Msg + 'Setup finished. These need a person:' + #13#10 + Attention + #13#10
      else
        Msg := Msg + 'Setup finished with nothing outstanding.' + #13#10#13#10;
    end;

    if HealthOk then
      Msg := Msg + 'The agent answered its health check. Leave this PC on while the queue drains — ' +
                   'use "Sync progress report" to watch it.'
    else
      Msg := Msg + 'Health check DID NOT respond yet. The service may still be starting. ' +
                   'Wait 30s and open http://127.0.0.1:17654/health; if it stays down, open the ' +
                   'logs folder and read .err.log.';

    MsgBox(Msg, mbInformation, MB_OK);

    if (SetupCode <> 0) or (AttentionLines(ReportPath) <> '') then
      if FileExists(ReportPath) then
        ShellExec('open', 'notepad.exe', '"' + ReportPath + '"', '', SW_SHOW, ewNoWait, SetupCode);
  end;
end;
