@echo off
cd /d "%~dp0"
if not exist out mkdir out
echo Compiling...
javac -encoding UTF-8 -d out -sourcepath src src\Main.java
if errorlevel 1 (
  echo.
  echo Compile failed. Is the JDK installed? Type: javac -version
  pause
  exit /b 1
)
echo Starting Smart Budget - open http://localhost:8080 in your browser
java -cp "out;lib\*" Main
pause
