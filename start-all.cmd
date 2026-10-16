@ECHO OFF
REM ============================================================
REM  One-click launcher for the three projects (portable).
REM
REM  Double-click this file. The real logic is in start-all.ps1;
REM  this wrapper only exists to invoke it with the right
REM  execution policy.
REM
REM  Re-runnable: services already running are skipped.
REM  Useful flags (passed straight through):
REM      start-all.cmd -ReinitDb     rebuild the three databases
REM      start-all.cmd -RedisOnly    only make sure Redis is up
REM
REM  This file is deliberately ASCII-only. cmd.exe reads .cmd
REM  files using the OEM codepage (GBK on a Chinese Windows), so
REM  any non-ASCII text here would render as garbage. All Chinese
REM  messages live in start-all.ps1, which is UTF-8 with BOM and is
REM  read correctly by PowerShell.
REM ============================================================

SETLOCAL

SET "PS1=%~dp0start-all.ps1"

IF NOT EXIST "%PS1%" (
  ECHO Cannot find "%PS1%"
  PAUSE
  EXIT /B 1
)

powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%" %*

ECHO.
ECHO Services run in their own windows and keep running after this one closes.
PAUSE > NUL
ENDLOCAL
