@echo off
rem Removes the I2P+ Windows service (see uninstall_service.ps1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall_service.ps1" %*
exit /b %errorlevel%