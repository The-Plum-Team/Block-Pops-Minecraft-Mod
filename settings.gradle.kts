pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.minecraftforge.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        gradlePluginPortal()
    }
}

// Read minecraft version - command line takes precedence over gradle.properties
val props = java.util.Properties().apply {
    file("gradle.properties").inputStream().use { load(it) }
}
val mcVersion = settings.extra.properties["minecraft_version"]?.toString()
    ?: gradle.startParameter.projectProperties["minecraft_version"]
    ?: props.getProperty("minecraft_version")
    ?: "1.20.1"

rootProject.name = "blockpops"

include("common")
include("fabric")

// Include Forge only for 1.20.x
if (mcVersion.startsWith("1.20")) {
    include("forge")
}

// Include NeoForge only for 1.21.x
if (mcVersion.startsWith("1.21")) {
    include("neoforge")
}
