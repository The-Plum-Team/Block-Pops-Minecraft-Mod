#!/bin/bash
echo "========================================"
echo "Building BlockPops for all versions"
echo "========================================"

# Create release directory
RELEASE_DIR="build/release"
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

echo ""
echo "[1/4] Building for Minecraft 1.20.1 (Fabric + Forge)..."
echo "----------------------------------------"
./gradlew clean build -Pminecraft_version=1.20.1 -Penabled_platforms=fabric,forge
if [ $? -ne 0 ]; then
    echo "ERROR: 1.20.1 build failed!"
    exit 1
fi

# Copy 1.20.1 JARs to release folder (exclude dev-shadow and sources)
cp "fabric/build/libs/BlockPops - Fabric - 1.20.1-"*.jar "$RELEASE_DIR/" 2>/dev/null
cp "forge/build/libs/BlockPops - Forge - 1.20.1-"*.jar "$RELEASE_DIR/" 2>/dev/null

# Remove dev-shadow and sources from release
rm -f "$RELEASE_DIR/"*-dev-shadow.jar "$RELEASE_DIR/"*-sources.jar 2>/dev/null

echo ""
echo "[2/4] Building for Minecraft 1.21.1 (Fabric + NeoForge)..."
echo "----------------------------------------"
./gradlew clean build -Pminecraft_version=1.21.1 -Penabled_platforms=fabric,neoforge
if [ $? -ne 0 ]; then
    echo "ERROR: 1.21.1 build failed!"
    exit 1
fi

# Copy 1.21.1 JARs to release folder (exclude dev-shadow and sources)
cp "fabric/build/libs/BlockPops - Fabric - 1.21.1-"*.jar "$RELEASE_DIR/" 2>/dev/null
cp "neoforge/build/libs/BlockPops - Neoforge - 1.21.1-"*.jar "$RELEASE_DIR/" 2>/dev/null

# Remove dev-shadow and sources from release
rm -f "$RELEASE_DIR/"*-dev-shadow.jar "$RELEASE_DIR/"*-sources.jar 2>/dev/null

echo ""
echo "[3/4] Building for Minecraft 1.21.4 (Fabric + NeoForge)..."
echo "----------------------------------------"
./gradlew clean build -Pminecraft_version=1.21.4 -Penabled_platforms=fabric,neoforge
if [ $? -ne 0 ]; then
    echo "ERROR: 1.21.4 build failed!"
    exit 1
fi

# Copy 1.21.4 JARs to release folder (exclude dev-shadow and sources)
cp "fabric/build/libs/BlockPops - Fabric - 1.21.4-"*.jar "$RELEASE_DIR/" 2>/dev/null
cp "neoforge/build/libs/BlockPops - Neoforge - 1.21.4-"*.jar "$RELEASE_DIR/" 2>/dev/null

# Remove dev-shadow and sources from release
rm -f "$RELEASE_DIR/"*-dev-shadow.jar "$RELEASE_DIR/"*-sources.jar 2>/dev/null

echo ""
echo "[4/4] Building for Minecraft 1.21.5 (Fabric + NeoForge)..."
echo "----------------------------------------"
./gradlew clean build -Pminecraft_version=1.21.5 -Penabled_platforms=fabric,neoforge
if [ $? -ne 0 ]; then
    echo "ERROR: 1.21.5 build failed!"
    exit 1
fi

# Copy 1.21.5 JARs to release folder (exclude dev-shadow and sources)
cp "fabric/build/libs/BlockPops - Fabric - 1.21.5-"*.jar "$RELEASE_DIR/" 2>/dev/null
cp "neoforge/build/libs/BlockPops - Neoforge - 1.21.5-"*.jar "$RELEASE_DIR/" 2>/dev/null

# Remove dev-shadow and sources from release
rm -f "$RELEASE_DIR/"*-dev-shadow.jar "$RELEASE_DIR/"*-sources.jar 2>/dev/null

echo ""
echo "========================================"
echo "BUILD COMPLETE - All versions built!"
echo "========================================"
echo ""
echo "All JARs collected in: $RELEASE_DIR/"
ls -1 "$RELEASE_DIR/"
echo ""
