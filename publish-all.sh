#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "===================================="
echo "Publishing BlockPops to Modrinth and CurseForge"
echo "===================================="
echo

# Check environment variables
echo "Checking environment variables..."
if [ -z "$MODRINTH_TOKEN" ]; then
    echo -e "${RED}ERROR: MODRINTH_TOKEN environment variable is not set!${NC}"
    echo "Please set it with: export MODRINTH_TOKEN=your_token_here"
    exit 1
fi

if [ -z "$CURSEFORGE_TOKEN" ]; then
    echo -e "${RED}ERROR: CURSEFORGE_TOKEN environment variable is not set!${NC}"
    echo "Please set it with: export CURSEFORGE_TOKEN=your_token_here"
    exit 1
fi

echo -e "${GREEN}Environment variables OK!${NC}"
echo

# Function to publish a version
publish_version() {
    local platform=$1
    local version=$2

    echo "===================================="
    echo -e "${YELLOW}Publishing $platform $version...${NC}"
    echo "===================================="

    ./gradlew :$platform:publishAll -Pminecraft_version=$version

    if [ $? -ne 0 ]; then
        echo -e "${RED}ERROR: $platform $version publish failed!${NC}"
        exit 1
    fi

    echo -e "${GREEN}$platform $version published successfully!${NC}"
    echo
}

# Publish all versions
publish_version "fabric" "1.20.1"
publish_version "fabric" "1.21.1"
publish_version "fabric" "1.21.4"
publish_version "fabric" "1.21.5"
publish_version "neoforge" "1.21.1"
publish_version "neoforge" "1.21.4"
publish_version "neoforge" "1.21.5"

echo "===================================="
echo -e "${GREEN}SUCCESS! All versions published!${NC}"
echo "===================================="
