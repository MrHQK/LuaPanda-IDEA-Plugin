@echo off
REM ============================================================================
REM 双击即可一键打包的 .bat 包装。所有逻辑在 pack.ps1。
REM 透传命令行参数： pack.bat -Clean -Deploy
REM ============================================================================
setlocal
pushd "%~dp0\.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0pack.ps1" %*
set EXITCODE=%ERRORLEVEL%
popd
echo.
pause
exit /b %EXITCODE%
