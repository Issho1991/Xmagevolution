@echo off
setlocal
cd /d "%~dp0"
set "LAB_PREVIEW_JAVA="
if not "%~1"=="" if exist "%~1" set "LAB_PREVIEW_JAVA=%~1"
if not defined LAB_PREVIEW_JAVA if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "LAB_PREVIEW_JAVA=%JAVA_HOME%\bin\java.exe"
if not defined LAB_PREVIEW_JAVA for /f "delims=" %%J in ('where java.exe 2^>nul') do if not defined LAB_PREVIEW_JAVA set "LAB_PREVIEW_JAVA=%%J"
if not defined LAB_PREVIEW_JAVA (
  echo Java non trovato. Trascina il file java.exe usato dal tuo XMage su questo CMD.
  pause
  exit /b 1
)
"%LAB_PREVIEW_JAVA%" -jar "prova\anteprima.jar"
if errorlevel 1 pause
