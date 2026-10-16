@echo off
rem =========================================================================
rem  LuaPanda IDEA Plugin - demo debuggee launcher
rem  Auto-detects a Lua runtime. No PATH setup, no manual run configuration.
rem
rem  Usage:
rem      run_demo.bat                  run all demo points
rem      run_demo.bat --only=3         run demo point 3 only
rem      run_demo.bat --mode=client    Client mode (device / remote)
rem      run_demo.bat --no-debug       no debugger, just print output
rem
rem  Detection order:
rem      1) env var LUA_EXE
rem      2) bundled portable runtime runtime\lua54\lua54.exe (has LuaSocket)
rem      3) lua54 / lua53 / lua on PATH
rem      4) local ZeroBrane Studio runtime (fallback)
rem =========================================================================
chcp 65001 >nul
setlocal enabledelayedexpansion
title LuaPanda Demo - Debuggee

cd /d "%~dp0"
set "DEMO_DIR=%CD%"
set "RUNTIME_DIR=%DEMO_DIR%\runtime\lua54"
set "ZBS_ROOT="

rem ---------- 1) explicit env var ----------
set "LUA_EXE=%LUA_EXE%"
if defined LUA_EXE if not exist "%LUA_EXE%" set "LUA_EXE="

rem ---------- 2) bundled portable runtime ----------
if not defined LUA_EXE (
    if exist "%RUNTIME_DIR%\lua54.exe" set "LUA_EXE=%RUNTIME_DIR%\lua54.exe"
)

rem ---------- 3) lua on PATH ----------
if not defined LUA_EXE for /f "delims=" %%P in ('where lua54.exe 2^>nul') do if not defined LUA_EXE set "LUA_EXE=%%P"
if not defined LUA_EXE for /f "delims=" %%P in ('where lua53.exe 2^>nul') do if not defined LUA_EXE set "LUA_EXE=%%P"
if not defined LUA_EXE for /f "delims=" %%P in ('where lua.exe   2^>nul') do if not defined LUA_EXE set "LUA_EXE=%%P"

rem ---------- 4) local ZeroBrane Studio runtime (fallback, relative to repo) ----------
if not defined LUA_EXE (
    if exist "%DEMO_DIR%\..\..\tmp\lua-runtime\zbs\bin\lua54.exe" (
        set "ZBS_ROOT=%DEMO_DIR%\..\..\tmp\lua-runtime\zbs"
        set "LUA_EXE=%ZBS_ROOT%\bin\lua54.exe"
    )
)

if not defined LUA_EXE (
    echo.
    echo [ERROR] No Lua runtime found.
    echo         Install Lua 5.1~5.4 and add it to PATH, or set LUA_EXE=path\to\lua.exe
    echo.
    if not "%DEMO_AUTO%"=="1" pause
    exit /b 1
)

rem ---------- module search paths: demo.lua / LuaPanda.lua / socket ----------
set "LUA_PATH=%DEMO_DIR%\?.lua;%DEMO_DIR%\?\init.lua;%RUNTIME_DIR%\lualibs\?.lua;%RUNTIME_DIR%\lualibs\?\init.lua;%LUA_PATH%"
set "LUA_CPATH=%RUNTIME_DIR%\clibs54\?.dll;%RUNTIME_DIR%\clibs54\?\core.dll;%LUA_CPATH%"
if defined ZBS_ROOT (
    set "LUA_PATH=%DEMO_DIR%\?.lua;%DEMO_DIR%\?\init.lua;%ZBS_ROOT%\lualibs\?.lua;%ZBS_ROOT%\lualibs\?\init.lua;%LUA_PATH%"
    set "LUA_CPATH=%ZBS_ROOT%\bin\clibs54\?.dll;%ZBS_ROOT%\bin\clibs54\?\core.dll;%LUA_CPATH%"
)

echo ==========================================================
echo  LuaPanda IDEA Plugin - Demo Debuggee
echo  lua      : %LUA_EXE%
echo  workdir  : %DEMO_DIR%
echo  args     : %*
echo ==========================================================
echo.

"%LUA_EXE%" demo.lua %*
set "EXIT_CODE=%ERRORLEVEL%"

echo.
echo [demo] lua process exited, code=%EXIT_CODE%

if not "%DEMO_AUTO%"=="1" pause
exit /b %EXIT_CODE%
