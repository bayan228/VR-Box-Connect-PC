@echo off
setlocal EnableExtensions DisableDelayedExpansion
title VR Box - Stereo Test
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\launch.ps1" -Mode Stereo %*
set "VRBOX_EXIT=%ERRORLEVEL%"
if not "%VRBOX_NO_PAUSE%"=="1" pause
exit /b %VRBOX_EXIT%
