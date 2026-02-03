# Publishing Guide for BlockPops

This guide explains how to automatically publish your mod to Modrinth and CurseForge.

## Prerequisites

### 1. Get Your API Tokens

**Modrinth:**
- Go to https://modrinth.com/settings/pats
- Create a new Personal Access Token with publishing permissions
- Save it securely

**CurseForge:**
- Go to https://legacy.curseforge.com/account/api-tokens
- Generate a new API token
- Save it securely

### 2. Set Up API Tokens Globally

**Recommended: Global Gradle Properties (Works for all your projects!)**

Edit or create: `C:\Users\YOUR_USERNAME\.gradle\gradle.properties` (Windows) or `~/.gradle/gradle.properties` (Linux/Mac)

Add:
```properties
modrinth_token=your_modrinth_token_here
curseforge_token=your_curseforge_token_here
```

This file is read by **all** your Gradle projects, so you only need to set it once!

**Alternative: Environment Variables (per-session)**

If you prefer environment variables instead:

**Windows (PowerShell):**
```powershell
$env:MODRINTH_TOKEN = "your_modrinth_token_here"
$env:CURSEFORGE_TOKEN = "your_curseforge_token_here"
```

**Linux/Mac:**
```bash
export MODRINTH_TOKEN="your_modrinth_token_here"
export CURSEFORGE_TOKEN="your_curseforge_token_here"
```

The system will check gradle.properties first, then fall back to environment variables.

### 3. Configure Project IDs

Edit `gradle.properties` and set:
```properties
modrinth_id=your-modrinth-project-slug
curseforge_id=123456  # Your CurseForge project ID (numeric)
```

## Publishing Commands

### Publish a Specific Version and Platform

**For Fabric 1.21.4:**
```bash
./gradlew :fabric:publishAll -Pminecraft_version=1.21.4
```

**For NeoForge 1.21.5:**
```bash
./gradlew :neoforge:publishAll -Pminecraft_version=1.21.5
```

### Publish to Only One Platform

**Modrinth only:**
```bash
./gradlew :fabric:modrinth -Pminecraft_version=1.21.4
```

**CurseForge only:**
```bash
./gradlew :fabric:publishCurseForge -Pminecraft_version=1.21.4
```

### Publish All Versions at Once

Create a script to publish all supported versions:

**Windows (publish-all.bat):**
```batch
@echo off
call gradlew :fabric:publishAll -Pminecraft_version=1.20.1
call gradlew :fabric:publishAll -Pminecraft_version=1.21.1
call gradlew :fabric:publishAll -Pminecraft_version=1.21.4
call gradlew :fabric:publishAll -Pminecraft_version=1.21.5
call gradlew :neoforge:publishAll -Pminecraft_version=1.21.1
call gradlew :neoforge:publishAll -Pminecraft_version=1.21.4
call gradlew :neoforge:publishAll -Pminecraft_version=1.21.5
echo All versions published!
```

**Linux/Mac (publish-all.sh):**
```bash
#!/bin/bash
./gradlew :fabric:publishAll -Pminecraft_version=1.20.1
./gradlew :fabric:publishAll -Pminecraft_version=1.21.1
./gradlew :fabric:publishAll -Pminecraft_version=1.21.4
./gradlew :fabric:publishAll -Pminecraft_version=1.21.5
./gradlew :neoforge:publishAll -Pminecraft_version=1.21.1
./gradlew :neoforge:publishAll -Pminecraft_version=1.21.4
./gradlew :neoforge:publishAll -Pminecraft_version=1.21.5
echo "All versions published!"
```

## Changelog Management

Update `CHANGELOG.md` before each release with your changes. The content will be automatically included in your Modrinth and CurseForge uploads.

## Version Type

By default, uploads are marked as **"release"**. To change this:

1. Edit `publish.gradle.kts`
2. Change the `versionType` (Modrinth) and `releaseType` (CurseForge):
   - `"release"` - Stable release
   - `"beta"` - Beta version
   - `"alpha"` - Alpha version

## Adding Dependencies

To specify mod dependencies (like Fabric API or GeckoLib):

### Modrinth Dependencies

Edit `publish.gradle.kts` in the `modrinth` section:
```kotlin
dependencies {
    required.project("fabric-api")  // Required dependency
    optional.project("geckolib")    // Optional dependency
}
```

### CurseForge Dependencies

Edit `publish.gradle.kts` in the `publishCurseForge` section:
```kotlin
mainFile.addRequirement("fabric-api")  // Required
mainFile.addOptional("geckolib")       // Optional
```

## Troubleshooting

### "API token not set" error
- Check if tokens are in `~/.gradle/gradle.properties` (global)
- Or verify environment variables are set in your current terminal session
- The system checks gradle.properties first, then environment variables
- Try closing and reopening your terminal after making changes

### "Project ID not found" error
- Verify your `modrinth_id` and `curseforge_id` in `gradle.properties`
- For Modrinth, use the project slug (e.g., "blockpops")
- For CurseForge, use the numeric project ID

### Build fails before publishing
- Run `./gradlew :fabric:build -Pminecraft_version=X.XX.X` first to verify the build works
- Check that all source files compile without errors

### Wrong game version or loader detected
- The script auto-detects versions based on the `minecraft_version` property
- Check `publish.gradle.kts` if you need to customize version detection

## CI/CD Integration

For GitHub Actions, add secrets:
1. Go to repository Settings → Secrets and variables → Actions
2. Add `MODRINTH_TOKEN` and `CURSEFORGE_TOKEN`
3. Create `.github/workflows/publish.yml`:

```yaml
name: Publish to Modrinth and CurseForge

on:
  release:
    types: [published]

jobs:
  publish:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v3
      - uses: actions/setup-java@v3
        with:
          java-version: '21'
          distribution: 'temurin'

      - name: Publish all versions
        env:
          MODRINTH_TOKEN: ${{ secrets.MODRINTH_TOKEN }}
          CURSEFORGE_TOKEN: ${{ secrets.CURSEFORGE_TOKEN }}
        run: |
          chmod +x gradlew
          ./gradlew :fabric:publishAll -Pminecraft_version=1.20.1
          ./gradlew :fabric:publishAll -Pminecraft_version=1.21.1
          ./gradlew :fabric:publishAll -Pminecraft_version=1.21.4
          ./gradlew :fabric:publishAll -Pminecraft_version=1.21.5
          ./gradlew :neoforge:publishAll -Pminecraft_version=1.21.1
          ./gradlew :neoforge:publishAll -Pminecraft_version=1.21.4
          ./gradlew :neoforge:publishAll -Pminecraft_version=1.21.5
```

## Support

If you encounter any issues with the publishing system, check:
- Modrinth documentation: https://docs.modrinth.com/
- CurseForge documentation: https://support.curseforge.com/
- Gradle Minotaur plugin: https://github.com/modrinth/minotaur
