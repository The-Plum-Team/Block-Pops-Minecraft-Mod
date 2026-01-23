pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://maven.neoforged.net/releases/")
        maven("https://files.minecraftforge.net/maven/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        gradlePluginPortal()
    }
}

rootProject.name = "blockpops"

include("common")
include("fabric")
include("forge")
