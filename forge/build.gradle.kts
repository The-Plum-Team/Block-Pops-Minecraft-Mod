plugins {
    id("dev.architectury.loom")
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
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
    minecraft("net.minecraft:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())

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

// ===== PUBLISHING CONFIGURATION =====

// Game versions for this build
val supportedGameVersions = listOf("1.20.1")

// Loaders (Forge only)
val modLoaders = listOf("forge")

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
    versionName.set("Block Pops ${project.version} [Forge] [MC $mcVersion]")
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
