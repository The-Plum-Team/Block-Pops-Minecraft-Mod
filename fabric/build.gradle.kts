plugins {
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
}

@Suppress("UNCHECKED_CAST")
val versionProp = rootProject.extra["versionProp"] as (String) -> String
val mcVersion = rootProject.extra["mcVersion"] as String

architectury {
    platformSetupLoomIde()
    fabric()
}

// Add version-specific source set for Minecraft API differences
val versionSourceSet = when {
    mcVersion == "1.21.5" -> "v1_21_5"
    mcVersion == "1.21.4" -> "v1_21_4"
    mcVersion.startsWith("1.21") -> "v1_21_1"
    else -> "v1_20_1"
}
sourceSets {
    main {
        java {
            srcDir("src/$versionSourceSet/java")
        }
        resources {
            srcDir("src/$versionSourceSet/resources")
        }
    }
}

val common: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["compileClasspath"].extendsFrom(common)
configurations["runtimeClasspath"].extendsFrom(common)
configurations.getByName("developmentFabric").extendsFrom(common)

val shadowBundle: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    minecraft("net.minecraft:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())

    modImplementation("net.fabricmc:fabric-loader:${versionProp("fabric_loader_version")}")

    // Fabric API
    modImplementation("net.fabricmc.fabric-api:fabric-api:${versionProp("fabric_api_version")}")

    // Architectury API - Fabric version
    modImplementation("dev.architectury:architectury-fabric:${versionProp("architectury_api_version")}")

    // GeckoLib - Fabric version
    modImplementation("software.bernie.geckolib:geckolib-fabric-$mcVersion:${versionProp("geckolib_version")}")

    // Common code
    common(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = ":common", configuration = "transformProductionFabric"))
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.property("minecraft_version", mcVersion)
    inputs.property("architectury_version", versionProp("architectury_api_version"))
    inputs.property("geckolib_version", versionProp("geckolib_version"))

    filesMatching("fabric.mod.json") {
        expand(
            "version" to inputs.properties["version"],
            "minecraft_version" to inputs.properties["minecraft_version"],
            "architectury_version" to inputs.properties["architectury_version"],
            "geckolib_version" to inputs.properties["geckolib_version"]
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
