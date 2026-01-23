plugins {
    id("com.gradleup.shadow")
}

@Suppress("UNCHECKED_CAST")
val versionProp = rootProject.extra["versionProp"] as (String) -> String
val mcVersion = rootProject.extra["mcVersion"] as String

loom {
    forge {
        mixinConfig("blockpops.mixins.json")
    }
}

architectury {
    platformSetupLoomIde()
    forge()
}

val common: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}
configurations["compileClasspath"].extendsFrom(common)
configurations["runtimeClasspath"].extendsFrom(common)
configurations.getByName("developmentForge").extendsFrom(common)

val shadowBundle: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
}

dependencies {
    forge("net.minecraftforge:forge:${versionProp("forge_version")}")

    // Architectury API
    modImplementation("dev.architectury:architectury-forge:${versionProp("architectury_api_version")}")

    // GeckoLib and its dependency
    modImplementation("software.bernie.geckolib:geckolib-forge-$mcVersion:${versionProp("geckolib_version")}")
    "forgeRuntimeLibrary"("com.eliotlash.mclib:mclib:20")
    modRuntimeOnly("com.eliotlash.mclib:mclib:20")

    common(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    shadowBundle(project(path = ":common", configuration = "transformProductionForge"))
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("META-INF/mods.toml") {
        expand("version" to inputs.properties["version"])
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(shadowBundle)
    archiveClassifier.set("dev-shadow")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    inputFile.set(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").flatMap { it.archiveFile })
}
