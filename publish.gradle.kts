// Publishing configuration for Modrinth and CurseForge
// This file is applied to both fabric and neoforge modules

import com.modrinth.minotaur.dependencies.ModDependency

val mcVersion = rootProject.extra["mcVersion"] as String

// Determine game versions supported by this build
val gameVersions = when {
    mcVersion == "1.21.5" -> listOf("1.21.5")
    mcVersion == "1.21.4" -> listOf("1.21.4")
    mcVersion == "1.21.1" -> listOf("1.21.1")
    mcVersion == "1.20.1" -> listOf("1.20.1")
    else -> listOf(mcVersion)
}

// Determine loaders for this platform
val loaders = when (project.name) {
    "fabric" -> listOf("fabric", "quilt")  // Fabric mods work on Quilt
    "neoforge" -> listOf("neoforge")
    "forge" -> listOf("forge")
    else -> emptyList()
}

// Read changelog
val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelog = if (changelogFile.exists()) {
    changelogFile.readText()
} else {
    "No changelog provided"
}

// Get API tokens from gradle.properties (checks both global ~/.gradle/gradle.properties and project gradle.properties)
val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

// ===== MODRINTH CONFIGURATION =====
configure<com.modrinth.minotaur.ModrinthExtension> {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}-${project.name}-$mcVersion")
    versionName.set("${project.version} [${project.name.replaceFirstChar { it.uppercase() }}] [MC $mcVersion]")
    versionType.set("release") // or "beta" or "alpha"
    uploadFile.set(tasks.named("remapJar"))
    gameVersions.addAll(gameVersions)
    loaders.addAll(loaders)
    changelog.set(changelog)

    // Dependencies (optional - add your mod dependencies here)
    // Example:
    // dependencies {
    //     required.project("fabric-api")
    //     optional.project("geckolib")
    // }
}

// ===== CURSEFORGE CONFIGURATION =====
tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseForge") {
    apiToken = curseforgeToken ?: ""

    val mainFile = upload(rootProject.property("curseforge_id") as String, tasks.named("remapJar").get().outputs.files.singleFile)
    mainFile.changelogType = "markdown"
    mainFile.changelog = changelog
    mainFile.releaseType = "release" // or "beta" or "alpha"

    // Add game versions
    gameVersions.forEach { version ->
        mainFile.addGameVersion(version)
    }

    // Add loaders
    loaders.forEach { loader ->
        mainFile.addModLoader(loader)
    }

    // Dependencies (optional - add your mod dependencies here)
    // mainFile.addRequirement("fabric-api") // for required mods
    // mainFile.addOptional("geckolib") // for optional mods

    doFirst {
        if (curseforgeToken.isNullOrEmpty()) {
            throw GradleException("CURSEFORGE_TOKEN is not set!")
        }
    }
}

// ===== COMBINED PUBLISH TASK =====
tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes to both Modrinth and CurseForge"
    dependsOn("modrinth", "publishCurseForge")

    doFirst {
        if (modrinthToken.isNullOrEmpty() && curseforgeToken.isNullOrEmpty()) {
            throw GradleException("Neither modrinth_token nor curseforge_token are set!")
        }
    }
}
