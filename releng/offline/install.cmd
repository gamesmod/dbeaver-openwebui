@echo off
rem Offline installer of "Open WebUI (OpenAI-compatible)" engine 2.2.0 for DBeaver CE.
rem Needs no internet and no Marketplace: uses the p2 director that is part of DBeaver.
rem Usage:  install.cmd "W:\app\dbeaver"      (folder that contains dbeaver.exe)
setlocal EnableExtensions

set "HERE=%~dp0"
set "SITE=%HERE%dbeaver-openwebui-ai-site-2.2.0.zip"
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
if not exist "%SITE%" (
  echo [ERROR] "%SITE%" not found. Keep install.cmd next to the site zip.
  goto :fail
)

tasklist /FI "IMAGENAME eq dbeaver.exe" 2>nul | find /I "dbeaver.exe" >nul
if not errorlevel 1 (
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

rem p2 profile of this DBeaver: plugins go to its own plugins folder, not to the OSGi cache
set "PROFILE=DefaultProfile"
for /f "tokens=2 delims==" %%P in ('findstr /B /C:"eclipse.p2.profile=" "%DBEAVER%\configuration\config.ini" 2^>nul') do set "PROFILE=%%P"
set P2ARGS=-destination "%DBEAVER%" -bundlepool "%DBEAVER%" -profile %PROFILE% -p2.os win32 -p2.ws win32 -p2.arch x86_64

rem Old versions are removed in the same p2 operation (director does not replace them itself).
rem Installed roots are taken from the p2 profile of this DBeaver.
set "UNINSTALL="
set "ROOTS=%TEMP%\openwebui-roots-%RANDOM%.txt"
pushd "%DBEAVER%"
"%JAVA%" -jar "%LAUNCHER%" -nosplash -application org.eclipse.equinox.p2.director %P2ARGS% -listInstalledRoots > "%ROOTS%" 2>nul
popd
for /f "tokens=1 delims=/" %%I in ('findstr /I /C:"openwebui" "%ROOTS%"') do call :addUninstall %%I
del "%ROOTS%" >nul 2>&1
set "UNINSTALL_ARGS="
if defined UNINSTALL set "UNINSTALL_ARGS=-uninstallIU %UNINSTALL%"

if not "%SITE: =%"=="%SITE%" (
  echo [ERROR] The path of this folder contains spaces: "%HERE%"
  echo         Unpack the package to a folder without spaces, e.g. C:\openwebui-offline
  goto :fail
)
rem The archive is unpacked first: with a jar:file:...zip!/ repository p2 leaves the jars in the OSGi cache
rem (configuration\org.eclipse.osgi\...) instead of copying them into the plugins folder of DBeaver.
set "SITEDIR=%HERE%site-unpacked"
if exist "%SITEDIR%" rmdir /s /q "%SITEDIR%"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%SITE%' -DestinationPath '%SITEDIR%' -Force"
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

findstr /I /C:"dbeaver.openwebui.ai," "%DBEAVER%\configuration\org.eclipse.equinox.simpleconfigurator\bundles.info" >nul
if errorlevel 1 (
  echo [ERROR] Plugin is not registered in bundles.info.
  goto :fail
)
if not exist "%DBEAVER%\plugins\dbeaver.openwebui.ai_*.jar" (
  echo [ERROR] Plugin jar was not copied to "%DBEAVER%\plugins".
  goto :fail
)

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
