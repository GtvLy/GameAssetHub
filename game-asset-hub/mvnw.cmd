@ECHO OFF
REM ============================================================
REM  Project-local Maven wrapper (template - copied to each
REM  backend as <project>\mvnw.cmd).
REM
REM  Maven is not installed on PATH; it ships with this repo in
REM  .tools\maven. All paths are derived from %~dp0, so moving the
REM  folder - or copying it to another machine - needs no edits.
REM
REM  Usage (run from the project root):
REM    mvnw.cmd clean test
REM    mvnw.cmd spring-boot:run
REM
REM  ASCII-only on purpose: cmd.exe reads .cmd files using the OEM
REM  codepage (GBK on a Chinese Windows), so non-ASCII text here
REM  would show up as garbage. Chinese messages belong in .ps1
REM  files, which are UTF-8 with BOM and read correctly.
REM ============================================================

SETLOCAL

SET "MVNW_HOME=%~dp0..\.tools\maven"
SET "MVNW_BIN=%MVNW_HOME%\bin\mvn.cmd"
SET "MVNW_REPO=%~dp0..\.tools\m2"

IF NOT EXIST "%MVNW_BIN%" (
  ECHO [mvnw] Maven not found: %MVNW_BIN%
  ECHO [mvnw] Expected the .tools\maven folder alongside the projects.
  EXIT /B 1
)

REM Pin the local repository to the one shipped in .tools\m2 so builds
REM work offline and never touch %%USERPROFILE%%\.m2.
REM It has to be passed here rather than set in conf\settings.xml:
REM Maven cannot resolve a relative path in <localRepository>, and an
REM absolute one would hard-code the drive letter.
SET "MVNW_REPO_OPT=-Dmaven.repo.local=%MVNW_REPO%"

REM Sources and SQL contain Chinese; force UTF-8 so javac does not fail
REM with "unmappable character" under the default GBK platform encoding.
SET "MAVEN_OPTS=-Dfile.encoding=UTF-8 %MAVEN_OPTS%"

CALL "%MVNW_BIN%" %MVNW_REPO_OPT% %*
EXIT /B %ERRORLEVEL%
