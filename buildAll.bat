@echo off
echo ========================================
echo Building BlockPops for all versions
echo ========================================

REM Create release directory
set RELEASE_DIR=build\release
if exist "%RELEASE_DIR%" rmdir /s /q "%RELEASE_DIR%"
mkdir "%RELEASE_DIR%"

echo.
echo [1/3] Building for Minecraft 1.20.1 (Fabric + Forge)...
echo ----------------------------------------
call "%~dp0gradlew.bat" clean build -Pminecraft_version=1.20.1 -Penabled_platforms=fabric,forge
if %ERRORLEVEL% neq 0 (
    echo ERROR: 1.20.1 build failed!
    exit /b 1
)

REM Copy 1.20.1 JARs to release folder
copy /Y "fabric\build\libs\BlockPops - Fabric - 1.20.1-*.jar" "%RELEASE_DIR%\" >nul 2>&1
copy /Y "forge\build\libs\BlockPops - Forge - 1.20.1-*.jar" "%RELEASE_DIR%\" >nul 2>&1

REM Remove dev-shadow and sources
del /q "%RELEASE_DIR%\*-dev-shadow.jar" 2>nul
del /q "%RELEASE_DIR%\*-sources.jar" 2>nul

echo.
echo [2/3] Building for Minecraft 1.21.1 (Fabric + NeoForge)...
echo ----------------------------------------
call "%~dp0gradlew.bat" clean build -Pminecraft_version=1.21.1 -Penabled_platforms=fabric,neoforge
if %ERRORLEVEL% neq 0 (
    echo ERROR: 1.21.1 build failed!
    exit /b 1
)

REM Copy 1.21.1 JARs to release folder
copy /Y "fabric\build\libs\BlockPops - Fabric - 1.21.1-*.jar" "%RELEASE_DIR%\" >nul 2>&1
copy /Y "neoforge\build\libs\BlockPops - Neoforge - 1.21.1-*.jar" "%RELEASE_DIR%\" >nul 2>&1

REM Remove dev-shadow and sources
del /q "%RELEASE_DIR%\*-dev-shadow.jar" 2>nul
del /q "%RELEASE_DIR%\*-sources.jar" 2>nul

echo.
echo [3/3] Building for Minecraft 1.21.4 (Fabric + NeoForge)...
echo ----------------------------------------
call "%~dp0gradlew.bat" clean build -Pminecraft_version=1.21.4 -Penabled_platforms=fabric,neoforge
if %ERRORLEVEL% neq 0 (
    echo ERROR: 1.21.4 build failed!
    exit /b 1
)

REM Copy 1.21.4 JARs to release folder
copy /Y "fabric\build\libs\BlockPops - Fabric - 1.21.4-*.jar" "%RELEASE_DIR%\" >nul 2>&1
copy /Y "neoforge\build\libs\BlockPops - Neoforge - 1.21.4-*.jar" "%RELEASE_DIR%\" >nul 2>&1

REM Remove dev-shadow and sources
del /q "%RELEASE_DIR%\*-dev-shadow.jar" 2>nul
del /q "%RELEASE_DIR%\*-sources.jar" 2>nul

echo.
echo ========================================
echo BUILD COMPLETE - All versions built!
echo ========================================
echo.
echo All JARs collected in: %RELEASE_DIR%\
dir /b "%RELEASE_DIR%"
echo.
