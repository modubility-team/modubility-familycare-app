@echo off
setlocal
set "ACTION=%~1"
if "%ACTION%"=="" set "ACTION=Menu"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\windows\FamilyCare.ps1" -Action "%ACTION%"
set "RESULT=%ERRORLEVEL%"
echo.
echo Read the result above. You may close this window after checking it.
pause
exit /b %RESULT%
