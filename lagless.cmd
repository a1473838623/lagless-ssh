@echo off
setlocal
rem Release layout uses lagless.jar. Source checkouts select separate editions.
set "LAGLESS_HOME=%~dp0"
set "LAGLESS_JAR=%LAGLESS_HOME%lagless.jar"
if exist "%LAGLESS_JAR%" goto :found
set "LAGLESS_JAR=%LAGLESS_HOME%cli\target\lagless-cli-standalone.jar"
if /I "%~1"=="gui" set "LAGLESS_JAR=%LAGLESS_HOME%desktop\target\lagless-desktop-standalone.jar"
:found
if not exist "%LAGLESS_JAR%" (
  echo lagless: selected edition is not built or installed.
  echo Terminal only: mvn -pl cli -am package; desktop: mvn package
  exit /b 1
)
call :apply
set "LAGLESS_JAVA=%LAGLESS_HOME%runtime\bin\java.exe"
if exist "%LAGLESS_JAVA%" goto :run
set "LAGLESS_JAVA=java"
where java >nul 2>&1
if errorlevel 1 (
  echo lagless: no Java found. Either download the bundled-runtime release,
  echo or install a JDK 21+ and make sure java is on PATH.
  exit /b 1
)
:run
set "LAGLESS_HEAP=128m"
if /I "%~1"=="gui" set "LAGLESS_HEAP=512m"
rem One JVM per window: keep each small. lagless needs a few MB of heap;
rem without a cap the JVM may grow to a quarter of the machine's memory.
"%LAGLESS_JAVA%" -Xmx%LAGLESS_HEAP% -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -jar "%LAGLESS_JAR%" %*
set "LAGLESS_EXIT=%ERRORLEVEL%"
rem Windows will not let a running JVM replace its own jar, so a downloaded
rem update waits beside it. Put it in place now that the JVM is gone, and
rem again before the next start in case this run was killed.
call :apply
exit /b %LAGLESS_EXIT%

:apply
if exist "%LAGLESS_JAR%.new" move /y "%LAGLESS_JAR%.new" "%LAGLESS_JAR%" >nul 2>&1
if exist "%LAGLESS_JAR%.old" del /q "%LAGLESS_JAR%.old" >nul 2>&1
goto :eof
