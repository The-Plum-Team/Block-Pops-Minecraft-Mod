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

architectury {
    platformSetupLoomIde()
    fabric()
}

// Add version-specific source set for Minecraft API differences
val versionSourceSet = when {
    mcVersion == "1.21.7" -> "v1_21_7"
    mcVersion == "1.21.6" -> "v1_21_6"
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

// ===== PUBLISHING CONFIGURATION =====

// Game versions for this build
val supportedGameVersions = when {
    mcVersion == "1.21.7" -> listOf("1.21.7")
    mcVersion == "1.21.6" -> listOf("1.21.6")
    mcVersion == "1.21.5" -> listOf("1.21.5")
    mcVersion == "1.21.4" -> listOf("1.21.4")
    mcVersion == "1.21.1" -> listOf("1.21.1")
    mcVersion == "1.20.1" -> listOf("1.20.1")
    else -> listOf(mcVersion)
}

// Loaders (Fabric only)
val modLoaders = listOf("fabric")

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
    versionName.set("Block Pops ${project.version} [Fabric] [MC $mcVersion]")
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
