@echo off
rem Offline installer of "Open WebUI (OpenAI-compatible)" engine for DBeaver CE.
rem Needs no internet and no Marketplace: uses the p2 director that is part of DBeaver.
rem Usage:  install.cmd "W:\app\dbeaver"      (folder that contains dbeaver.exe)
setlocal EnableExtensions
rem Child commands (pipes, for /f) are started through %ComSpec%. Some machines have it overridden,
rem so the script uses the real cmd.exe and avoids child commands where possible (temp files instead).
set "ComSpec=%SystemRoot%\system32\cmd.exe"
set "SYS=%SystemRoot%\system32"
set "TMPF=%TEMP%\openwebui-install-%RANDOM%%RANDOM%"

set "HERE=%~dp0"
set "SITE="
for %%F in ("%HERE%dbeaver-openwebui-ai-site-*.zip") do set "SITE=%%~fF"
set "DBEAVER=%~1"

if "%DBEAVER%"=="" if exist "%ProgramFiles%\DBeaver\dbeaver.exe" set "DBEAVER=%ProgramFiles%\DBeaver"
if "%DBEAVER%"=="" if exist "%LOCALAPPDATA%\DBeaver\dbeaver.exe" set "DBEAVER=%LOCALAPPDATA%\DBeaver"
if "%DBEAVER%"=="" set /p "DBEAVER=DBeaver folder (with dbeaver.exe): "
set "DBEAVER=%DBEAVER:/=\%"
if "%DBEAVER:~-1%"=="\" set "DBEAVER=%DBEAVER:~0,-1%"

if not exist "%DBEAVER%\dbeaver.exe" (
  echo [ERROR] dbeaver.exe not found in "%DBEAVER%"
  goto :fail
)
if not defined SITE set "SITE=%HERE%dbeaver-openwebui-ai-site-*.zip"
if not exist "%SITE%" (
  echo [ERROR] "%SITE%" not found. Keep install.cmd next to the site zip.
  goto :fail
)

"%SYS%\tasklist.exe" /FI "IMAGENAME eq dbeaver.exe" /NH > "%TMPF%.tasks" 2>nul
"%SYS%\find.exe" /I "dbeaver.exe" "%TMPF%.tasks" >nul 2>&1
set "RUNNING=%ERRORLEVEL%"
del "%TMPF%.tasks" >nul 2>&1
if "%RUNNING%"=="0" (
  echo [ERROR] DBeaver is running. Close it and run install.cmd again.
  goto :fail
)

set "JAVA=%DBEAVER%\jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
set "LAUNCHER="
for %%F in ("%DBEAVER%\plugins\org.eclipse.equinox.launcher_*.jar") do set "LAUNCHER=%%~fF"
if "%LAUNCHER%"=="" (
  echo [ERROR] org.eclipse.equinox.launcher_*.jar not found in "%DBEAVER%\plugins"
  goto :fail
)

rem Check that the DBeaver folder is writable (p2 installs into it)
echo test> "%DBEAVER%\configuration\.openwebui-write-test" 2>nul
if not exist "%DBEAVER%\configuration\.openwebui-write-test" (
  echo [ERROR] No write access to "%DBEAVER%". Run install.cmd as administrator
  echo         or ask the administrator of this folder.
  goto :fail
)
del "%DBEAVER%\configuration\.openwebui-write-test" >nul 2>&1

rem Install into the p2 profile of this DBeaver (the one it uses for its own updates)
set "PROFILE=DefaultProfile"
"%SYS%\findstr.exe" /B /C:"eclipse.p2.profile=" "%DBEAVER%\configuration\config.ini" > "%TMPF%.profile" 2>nul
for /f "usebackq tokens=2 delims==" %%P in ("%TMPF%.profile") do set "PROFILE=%%P"
del "%TMPF%.profile" >nul 2>&1
set P2ARGS=-destination "%DBEAVER%" -bundlepool "%DBEAVER%" -profile %PROFILE% -p2.os win32 -p2.ws win32 -p2.arch x86_64

rem Old versions are removed in the same p2 operation (director does not replace them itself).
rem Installed roots are taken from the p2 profile of this DBeaver.
set "UNINSTALL="
set "ROOTS=%TMPF%.roots"
pushd "%DBEAVER%"
"%JAVA%" -jar "%LAUNCHER%" -nosplash -application org.eclipse.equinox.p2.director %P2ARGS% -listInstalledRoots > "%ROOTS%" 2>nul
popd
"%SYS%\findstr.exe" /I /C:"openwebui" "%ROOTS%" > "%ROOTS%.ours" 2>nul
for /f "usebackq tokens=1 delims=/" %%I in ("%ROOTS%.ours") do call :addUninstall %%I
del "%ROOTS%" "%ROOTS%.ours" >nul 2>&1
set "UNINSTALL_ARGS="
if defined UNINSTALL set "UNINSTALL_ARGS=-uninstallIU %UNINSTALL%"

if not "%SITE: =%"=="%SITE%" (
  echo [ERROR] The path of this folder contains spaces: "%HERE%"
  echo         Unpack the package to a folder without spaces, e.g. C:\openwebui-offline
  goto :fail
)
rem The archive is unpacked first: plain folder repository, no jar: URL quirks.
set "SITEDIR=%HERE%site-unpacked"
if exist "%SITEDIR%" rmdir /s /q "%SITEDIR%"
"%SYS%\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%SITE%' -DestinationPath '%SITEDIR%' -Force"
if not exist "%SITEDIR%\artifacts.xml" if not exist "%SITEDIR%\artifacts.jar" (
  echo [ERROR] Cannot unpack "%SITE%" to "%SITEDIR%"
  goto :fail
)
set "SITEURL=%SITEDIR:\=/%"

echo DBeaver : %DBEAVER%
echo Site    : %SITE%
echo Profile : %PROFILE%
if defined UNINSTALL echo Remove  : %UNINSTALL%
echo.

pushd "%DBEAVER%"
"%JAVA%" -Declipse.p2.mirrors=false -jar "%LAUNCHER%" -nosplash -consoleLog ^
  -application org.eclipse.equinox.p2.director %P2ARGS% ^
  -repository "file:/%SITEURL%/" ^
  %UNINSTALL_ARGS% ^
  -installIU dbeaver.openwebui.ai.feature.feature.group
set "RC=%ERRORLEVEL%"
popd
rmdir /s /q "%SITEDIR%" >nul 2>&1
if not "%RC%"=="0" (
  echo.
  echo [ERROR] p2 director failed, code %RC%. See the messages above.
  goto :fail
)

set "BI=%DBEAVER%\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info"
set "JARPATH="
"%SYS%\findstr.exe" /B /C:"dbeaver.openwebui.ai," "%BI%" > "%TMPF%.bi" 2>nul
for /f "usebackq tokens=3 delims=," %%L in ("%TMPF%.bi") do set "JARPATH=%%L"
del "%TMPF%.bi" >nul 2>&1
if not defined JARPATH (
  echo [ERROR] Plugin is not registered in bundles.info.
  goto :fail
)
set "JARPATH=%JARPATH:/=\%"
if not exist "%DBEAVER%\%JARPATH%" if not exist "%JARPATH%" (
  echo [ERROR] Registered plugin file is missing: %JARPATH%
  goto :fail
)
echo Plugin  : %JARPATH%

echo.
echo [OK] Plugin installed. Start DBeaver: AI settings - engine "Open WebUI (OpenAI-compatible)".
if not defined OPENWEBUI_NOPAUSE pause
exit /b 0

:addUninstall
if defined UNINSTALL (set "UNINSTALL=%UNINSTALL%,%~1") else set "UNINSTALL=%~1"
exit /b 0

:fail
if not defined OPENWEBUI_NOPAUSE pause
exit /b 1
