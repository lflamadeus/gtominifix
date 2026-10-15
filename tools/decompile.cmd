@echo off
rem Launcher for decompile.ps1 (bypasses the script execution policy).
rem Keep this file ASCII-only: cmd.exe reads .cmd as ANSI, UTF-8 comments break it.
rem Usage is identical to decompile.ps1, e.g.
rem   tools\decompile.cmd -List
rem   tools\decompile.cmd gtceu pipelike\fluid
setlocal
set "PS=%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe"
if exist "%ProgramFiles%\PowerShell\7\pwsh.exe" set "PS=%ProgramFiles%\PowerShell\7\pwsh.exe"
"%PS%" -NoProfile -ExecutionPolicy Bypass -File "%~dp0decompile.ps1" %*
