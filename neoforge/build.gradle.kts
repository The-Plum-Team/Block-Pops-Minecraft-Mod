plugins {
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
}

// Publishing configuration will be added below

@Suppress("UNCHECKED_CAST")
val versionProp = rootProject.extra["versionProp"] as (String) -> String
val mcVersion = rootProject.extra["mcVersion"] as String

// Configure loom for NeoForge - this should create the neoForge configuration
loom {
    neoForge {
        // NeoForge mixin is configured via neoforge.mods.toml
    }
}

architectury {
    platformSetupLoomIde()
    neoForge()
}

// Add version-specific source set for Minecraft API differences
// For 1.21.5, we replace the main source with v1_21_5 (GeckoLib 5.x)
// For 1.21.4, we replace the main source with v1_21_4 (different API for item rendering, block entities)
// For 1.21.1, we use the main source set as-is
val versionSourceSet = when {
    mcVersion == "1.21.7" -> "v1_21_7"
    mcVersion == "1.21.6" -> "v1_21_6"
    mcVersion == "1.21.5" -> "v1_21_5"
    mcVersion == "1.21.4" -> "v1_21_4"
    mcVersion.startsWith("1.21") -> null  // Use main source set
    else -> null  // NeoForge is for 1.21+ only
}
sourceSets {
    main {
        java {
            if (versionSourceSet != null) {
                // Replace main sources with version-specific sources
                setSrcDirs(listOf("src/$versionSourceSet/java"))
            }
        }
        resources {
            if (versionSourceSet != null) {
                // Add version-specific resources (for item model JSON files in 1.21.4+)
                srcDir("src/$versionSourceSet/resources")
            }
        }
    }
}

val common: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["compileClasspath"].extendsFrom(common)
configurations["runtimeClasspath"].extendsFrom(common)
configurations.getByName("developmentNeoForge").extendsFrom(common)

val shadowBundle: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    minecraft("net.minecraft:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())

    // NeoForge dependency
    "neoForge"("net.neoforged:neoforge:${versionProp("neoforge_version")}")

    // Architectury API - NeoForge version
    modImplementation("dev.architectury:architectury-neoforge:${versionProp("architectury_api_version")}")

    // GeckoLib - NeoForge version
    modImplementation("software.bernie.geckolib:geckolib-neoforge-$mcVersion:${versionProp("geckolib_version")}")

    common(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = ":common", configuration = "transformProductionNeoForge"))
}

// Calculate NeoForge loader version range based on Minecraft version
val neoforgeLoaderVersion = when {
    mcVersion == "1.21.7" -> "[4,)"
    mcVersion == "1.21.6" -> "[4,)"
    mcVersion == "1.21.5" -> "[4,)"
    mcVersion == "1.21.4" -> "[4,)"
    mcVersion.startsWith("1.21") -> "[4,)"
    else -> "[4,)"
}

// Calculate NeoForge version range based on Minecraft version
val neoforgeVersionRange = when {
    mcVersion == "1.21.7" -> "[21.7,)"
    mcVersion == "1.21.6" -> "[21.6,)"
    mcVersion == "1.21.5" -> "[21.5,)"
    mcVersion == "1.21.4" -> "[21.4,)"
    mcVersion.startsWith("1.21") -> "[21.1,)"
    else -> "[21.1,)"
}

// Calculate Minecraft version range
val minecraftVersionRange = when {
    mcVersion == "1.21.7" -> "[1.21.7,1.22)"
    mcVersion == "1.21.6" -> "[1.21.6,1.22)"
    mcVersion == "1.21.5" -> "[1.21.5,1.22)"
    mcVersion == "1.21.4" -> "[1.21.4,1.22)"
    mcVersion.startsWith("1.21") -> "[1.21.1,1.22)"
    else -> "[1.21.1,1.22)"
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("loader_version", neoforgeLoaderVersion)
    inputs.property("neoforge_version_range", neoforgeVersionRange)
    inputs.property("minecraft_version_range", minecraftVersionRange)
    inputs.property("architectury_version", versionProp("architectury_api_version"))

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(
            "version" to inputs.properties["version"],
            "loader_version" to inputs.properties["loader_version"],
            "neoforge_version_range" to inputs.properties["neoforge_version_range"],
            "minecraft_version_range" to inputs.properties["minecraft_version_range"],
            "architectury_version" to inputs.properties["architectury_version"]
        )
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("dev-shadow")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
}

// ===== PUBLISHING CONFIGURATION =====

// Game versions for this build
val supportedGameVersions = when {
    mcVersion == "1.21.7" -> listOf("1.21.7")
    mcVersion == "1.21.6" -> listOf("1.21.6")
    mcVersion == "1.21.5" -> listOf("1.21.5")
    mcVersion == "1.21.4" -> listOf("1.21.4")
    mcVersion.startsWith("1.21") -> listOf("1.21.1")
    else -> emptyList()
}

// Loaders (NeoForge only)
val modLoaders = listOf("neoforge")

// Read changelog
val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelogText = if (changelogFile.exists()) {
    changelogFile.readText()
} else {
    "No changelog provided"
}

// Get API tokens
val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

// Modrinth Configuration
modrinth {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}")
    versionName.set("Block Pops ${project.version} [NeoForge] [MC $mcVersion]")
    versionType.set("release")
    uploadFile.set(tasks.named("remapJar"))
    gameVersions.addAll(supportedGameVersions)
    loaders.addAll(modLoaders)
    changelog.set(changelogText)
}

// CurseForge Configuration
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseForge") {
    dependsOn(tasks.named("remapJar"))
    apiToken = curseforgeToken ?: ""

    val mainFile = upload(rootProject.property("curseforge_id") as String, tasks.named("remapJar").get().outputs.files.singleFile)
    mainFile.changelogType = "markdown"
    mainFile.changelog = changelogText
    mainFile.releaseType = "release"

    supportedGameVersions.forEach { version ->
        mainFile.addGameVersion(version)
    }

    modLoaders.forEach { loader ->
        mainFile.addModLoader(loader)
    }

    doFirst {
        if (curseforgeToken.isNullOrEmpty()) {
            throw GradleException("curseforge_token is not set!")
        }
    }
}

// Combined Publish Task
tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes to both Modrinth and CurseForge"
    dependsOn("modrinth", "publishCurseForge")
}
