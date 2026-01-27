plugins {
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
}

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
    mcVersion == "1.21.5" -> "[4,)"
    mcVersion == "1.21.4" -> "[4,)"
    mcVersion.startsWith("1.21") -> "[4,)"
    else -> "[4,)"
}

// Calculate NeoForge version range based on Minecraft version
val neoforgeVersionRange = when {
    mcVersion == "1.21.5" -> "[21.5,)"
    mcVersion == "1.21.4" -> "[21.4,)"
    mcVersion.startsWith("1.21") -> "[21.1,)"
    else -> "[21.1,)"
}

// Calculate Minecraft version range
val minecraftVersionRange = when {
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
