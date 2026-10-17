@echo off
cd /d "%~dp0"
call mvn "-Dmaven.repo.local=%USERPROFILE%/.m2/repository" clean verify %*
if errorlevel 1 pause
