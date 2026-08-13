@echo off
rem Creates the I2P+ Start Menu and desktop shortcuts (see create_shortcuts.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0create_shortcuts.ps1" %*
exit /b %errorlevel%