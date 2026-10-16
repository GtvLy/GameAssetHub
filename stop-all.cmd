@ECHO OFF
REM ============================================================
REM  One-click stop for the three projects.
REM
REM  Double-click this file. The real logic is in stop-all.ps1;
REM  this wrapper only exists to invoke it with the right
REM  execution policy.
REM
REM  This file is deliberately ASCII-only. cmd.exe reads .cmd
REM  files using the OEM codepage (GBK on a Chinese Windows), so
REM  any non-ASCII text here would render as garbage. All Chinese
REM  messages live in stop-all.ps1, which is UTF-8 with BOM and is
REM  read correctly by PowerShell.
REM ============================================================

SETLOCAL

SET "PS1=%~dp0stop-all.ps1"

IF NOT EXIST "%PS1%" (
  ECHO Cannot find "%PS1%"
  PAUSE
  EXIT /B 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*

ECHO.
ECHO Done. Press any key to close this window.
PAUSE > NUL
ENDLOCAL
