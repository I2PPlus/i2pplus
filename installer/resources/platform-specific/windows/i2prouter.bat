@echo off
rem Start the I2P+ router in this console window. 64-bit only.
if not exist "%~dp0I2Psvc.exe" (
    echo I2Psvc.exe not found in "%~dp0"
    pause
    exit /b 1
)
"%~dp0I2Psvc.exe" -c "%~dp0wrapper.config"