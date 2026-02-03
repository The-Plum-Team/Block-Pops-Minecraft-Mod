@echo off
echo ====================================
echo Publishing BlockPops to Modrinth and CurseForge
echo ====================================
echo.

echo Checking environment variables...
if "%MODRINTH_TOKEN%"=="" (
    echo ERROR: MODRINTH_TOKEN environment variable is not set!
    echo Please set it with: set MODRINTH_TOKEN=your_token_here
    pause
    exit /b 1
)

if "%CURSEFORGE_TOKEN%"=="" (
    echo ERROR: CURSEFORGE_TOKEN environment variable is not set!
    echo Please set it with: set CURSEFORGE_TOKEN=your_token_here
    pause
    exit /b 1
)

echo Environment variables OK!
echo.

echo ====================================
echo Publishing Fabric 1.20.1...
echo ====================================
call gradlew.bat :fabric:publishAll -Pminecraft_version=1.20.1
if errorlevel 1 (
    echo ERROR: Fabric 1.20.1 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing Fabric 1.21.1...
echo ====================================
call gradlew.bat :fabric:publishAll -Pminecraft_version=1.21.1
if errorlevel 1 (
    echo ERROR: Fabric 1.21.1 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing Fabric 1.21.4...
echo ====================================
call gradlew.bat :fabric:publishAll -Pminecraft_version=1.21.4
if errorlevel 1 (
    echo ERROR: Fabric 1.21.4 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing Fabric 1.21.5...
echo ====================================
call gradlew.bat :fabric:publishAll -Pminecraft_version=1.21.5
if errorlevel 1 (
    echo ERROR: Fabric 1.21.5 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing NeoForge 1.21.1...
echo ====================================
call gradlew.bat :neoforge:publishAll -Pminecraft_version=1.21.1
if errorlevel 1 (
    echo ERROR: NeoForge 1.21.1 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing NeoForge 1.21.4...
echo ====================================
call gradlew.bat :neoforge:publishAll -Pminecraft_version=1.21.4
if errorlevel 1 (
    echo ERROR: NeoForge 1.21.4 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo Publishing NeoForge 1.21.5...
echo ====================================
call gradlew.bat :neoforge:publishAll -Pminecraft_version=1.21.5
if errorlevel 1 (
    echo ERROR: NeoForge 1.21.5 publish failed!
    pause
    exit /b 1
)

echo.
echo ====================================
echo SUCCESS! All versions published!
echo ====================================
pause
