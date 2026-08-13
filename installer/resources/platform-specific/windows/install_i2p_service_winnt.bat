@echo off
rem Installs the I2P+ Windows service (see install_service.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install_service.ps1" %*
exit /b %errorlevel%