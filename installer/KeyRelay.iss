#define AppVersion "0.1.0"

[Setup]
AppId={{5B7C9E2A-3F41-4D8B-9C6E-1A2B3C4D5E6F}
AppName=KeyRelay
AppVersion={#AppVersion}
AppPublisher=KeyRelay
DefaultDirName={autopf}\KeyRelay
DisableProgramGroupPage=yes
PrivilegesRequired=admin
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir=..\artifacts\installer
OutputBaseFilename=KeyRelay-Setup-{#AppVersion}
SetupIconFile=..\windows\src\KeyRelay.Tray\Assets\KeyRelay.ico
UninstallDisplayIcon={app}\Tray\KeyRelay.Tray.exe
Compression=lzma2
SolidCompression=yes
WizardStyle=modern

[Languages]
Name: "turkish"; MessagesFile: "compiler:Languages\Turkish.isl"
Name: "english"; MessagesFile: "compiler:Default.isl"

[Files]
Source: "..\artifacts\layout\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\KeyRelay"; Filename: "{app}\Tray\KeyRelay.Tray.exe"

[Run]
Filename: "{app}\Tray\KeyRelay.Tray.exe"; Flags: nowait postinstall skipifsilent runasoriginaluser; Description: "{cm:LaunchProgram,KeyRelay}"

[UninstallRun]
Filename: "{sys}\taskkill.exe"; Parameters: "/im KeyRelay.Tray.exe /f"; Flags: runhidden; RunOnceId: "KeyRelayTray"
Filename: "{app}\KeyRelay.Service.exe"; Parameters: "uninstall"; Flags: runhidden waituntilterminated; RunOnceId: "KeyRelayUninstall"

[Code]
var
  InstallExitCode: Integer;

// Upgrades replace files in use: stop the service (it ends the agent) and the tray first.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  Code: Integer;
begin
  Exec(ExpandConstant('{sys}\WindowsPowerShell\v1.0\powershell.exe'), '-NoProfile -Command "Stop-Service KeyRelay -ErrorAction SilentlyContinue"', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Exec(ExpandConstant('{sys}\taskkill.exe'), '/im KeyRelay.Tray.exe /f', '', SW_HIDE, ewWaitUntilTerminated, Code);
  Result := '';
end;

// A [Run] entry ignores the exit code; a failed "install" must fail the setup (silent installs included).
procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep <> ssPostInstall then Exit;
  if not Exec(ExpandConstant('{app}\KeyRelay.Service.exe'), 'install', '', SW_HIDE, ewWaitUntilTerminated, InstallExitCode) then
    InstallExitCode := -1;
  if InstallExitCode <> 0 then
  begin
    Log(Format('KeyRelay.Service.exe install failed, exit code %d', [InstallExitCode]));
    SuppressibleMsgBox(Format('KeyRelay service setup failed (exit code %d).', [InstallExitCode]), mbError, MB_OK, IDOK);
  end;
end;

function GetCustomSetupExitCode: Integer;
begin
  Result := InstallExitCode;
end;
