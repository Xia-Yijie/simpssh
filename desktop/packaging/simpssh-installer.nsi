Unicode true
ManifestDPIAware true
RequestExecutionLevel user

!include "MUI2.nsh"
!include "LogicLib.nsh"
!include "nsDialogs.nsh"
!include "x64.nsh"

!define APP_NAME "simpssh"
; APP_VERSION is injected by build-installer.ps1 (reads tauri.conf.json).
!define APP_PUBLISHER "Xia-Yijie"
!define APP_EXE "simpssh.exe"
!define UNINSTALL_EXE "Uninstall simpssh.exe"
!define WEBVIEW2_GUID "{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}"

Name "${APP_NAME}"
OutFile "..\..\release\simpssh_${APP_VERSION}_x64-setup.exe"
InstallDir "$LOCALAPPDATA\Programs\simpssh"
InstallDirRegKey HKCU "Software\simpssh" "InstallDir"
BrandingText "simpssh installer"

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "CompanyName" "${APP_PUBLISHER}"
VIAddVersionKey "FileDescription" "${APP_NAME} installer"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"

Icon "..\src-tauri\icons\icon.ico"
UninstallIcon "..\src-tauri\icons\icon.ico"

Var WebView2Version
Var CreateDesktopShortcut
Var PinTaskbarShortcut
Var DesktopCheckbox
Var TaskbarCheckbox

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_DIRECTORY
Page Custom ShortcutPageCreate ShortcutPageLeave
!insertmacro MUI_PAGE_INSTFILES
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Launch simpssh"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "SimpChinese"
!insertmacro MUI_LANGUAGE "English"

Function DetectWebView2
  StrCpy $WebView2Version ""

  ${If} ${RunningX64}
    ReadRegStr $WebView2Version HKLM "SOFTWARE\WOW6432Node\Microsoft\EdgeUpdate\Clients\${WEBVIEW2_GUID}" "pv"
  ${Else}
    ReadRegStr $WebView2Version HKLM "SOFTWARE\Microsoft\EdgeUpdate\Clients\${WEBVIEW2_GUID}" "pv"
  ${EndIf}

  ${If} $WebView2Version == ""
    ReadRegStr $WebView2Version HKCU "Software\Microsoft\EdgeUpdate\Clients\${WEBVIEW2_GUID}" "pv"
  ${EndIf}
FunctionEnd

Function ShortcutPageCreate
  !insertmacro MUI_HEADER_TEXT "快捷方式" "选择是否创建桌面或任务栏入口"

  nsDialogs::Create 1018
  Pop $0
  ${If} $0 == error
    Abort
  ${EndIf}

  ${NSD_CreateLabel} 0 0 100% 24u "Start Menu shortcut will always be created. Optional shortcuts:"
  Pop $0

  ${NSD_CreateCheckbox} 0 28u 100% 12u "Create desktop shortcut"
  Pop $DesktopCheckbox

  ${NSD_CreateCheckbox} 0 48u 100% 12u "Try pinning to taskbar"
  Pop $TaskbarCheckbox

  ${NSD_CreateLabel} 0 68u 100% 24u "Taskbar pinning is best-effort on modern Windows and will not block install if it fails."
  Pop $0

  nsDialogs::Show
FunctionEnd

Function ShortcutPageLeave
  ${NSD_GetState} $DesktopCheckbox $CreateDesktopShortcut
  ${NSD_GetState} $TaskbarCheckbox $PinTaskbarShortcut
FunctionEnd

Section "Install"
  SetOutPath "$INSTDIR"

  File /oname=${APP_EXE} "..\src-tauri\target\release\simpssh-desktop.exe"
  File "..\src-tauri\MicrosoftEdgeWebview2Setup.exe"

  Call DetectWebView2
  ${If} $WebView2Version == ""
    DetailPrint "WebView2 runtime not found. Installing bootstrapper..."
    ExecWait '"$INSTDIR\MicrosoftEdgeWebview2Setup.exe" /silent /install'
  ${Else}
    DetailPrint "WebView2 runtime version $WebView2Version detected."
  ${EndIf}

  WriteRegStr HKCU "Software\simpssh" "InstallDir" "$INSTDIR"

  CreateDirectory "$SMPROGRAMS\simpssh"
  CreateShortcut "$SMPROGRAMS\simpssh\simpssh.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}"
  CreateShortcut "$SMPROGRAMS\simpssh\Uninstall simpssh.lnk" "$INSTDIR\${UNINSTALL_EXE}"

  ${If} $CreateDesktopShortcut == ${BST_CHECKED}
    CreateShortcut "$DESKTOP\simpssh.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}"
  ${EndIf}

  ${If} $PinTaskbarShortcut == ${BST_CHECKED}
    nsExec::ExecToLog 'powershell -NoProfile -ExecutionPolicy Bypass -Command "$$target = ''$INSTDIR\${APP_EXE}''; try { $$shell = New-Object -ComObject Shell.Application; $$folder = $$shell.Namespace([System.IO.Path]::GetDirectoryName($$target)); $$item = $$folder.ParseName([System.IO.Path]::GetFileName($$target)); if ($$item) { $$item.InvokeVerb(''taskbarpin'') } } catch { exit 0 }"'
  ${EndIf}

  WriteUninstaller "$INSTDIR\${UNINSTALL_EXE}"

  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "DisplayIcon" "$INSTDIR\${APP_EXE}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "UninstallString" "$INSTDIR\${UNINSTALL_EXE}"
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "QuietUninstallString" '"$INSTDIR\${UNINSTALL_EXE}" /S'
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "NoModify" 1
  WriteRegDWORD HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Delete "$DESKTOP\simpssh.lnk"
  Delete "$SMPROGRAMS\simpssh\simpssh.lnk"
  Delete "$SMPROGRAMS\simpssh\Uninstall simpssh.lnk"
  RMDir "$SMPROGRAMS\simpssh"

  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\MicrosoftEdgeWebview2Setup.exe"
  Delete "$INSTDIR\${UNINSTALL_EXE}"
  RMDir "$INSTDIR"

  DeleteRegKey HKCU "Software\simpssh"
  DeleteRegKey HKCU "Software\Microsoft\Windows\CurrentVersion\Uninstall\simpssh"
SectionEnd
