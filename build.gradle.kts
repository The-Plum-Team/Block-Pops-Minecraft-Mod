plugins {
    id("dev.architectury.loom") version "1.11-SNAPSHOT" apply false
    id("architectury-plugin") version "3.4-SNAPSHOT"
    id("com.gradleup.shadow") version "8.3.6" apply false
}

// Get active MC version
val mcVersion: String = property("minecraft_version") as String
val mcVersionUnderscored = mcVersion.replace(".", "_")

// Helper to get version-specific property
fun versionProp(baseName: String): String {
    val key = "${baseName}_${mcVersionUnderscored}"
    return findProperty(key)?.toString()
        ?: throw GradleException("Property '$key' not found in gradle.properties")
}

// Java version based on MC version
val javaVersion = if (mcVersion.startsWith("1.20")) JavaVersion.VERSION_17 else JavaVersion.VERSION_21
val javaRelease = if (mcVersion.startsWith("1.20")) 17 else 21

architectury {
    minecraft = mcVersion
}

allprojects {
    group = property("maven_group") as String
    version = property("mod_version") as String
}

subprojects {
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "architectury-plugin")
    apply(plugin = "maven-publish")
    apply(plugin = "java")

    extensions.configure<BasePluginExtension> {
        archivesName.set(when (project.name) {
            "forge" -> "BlockPops - Forge - $mcVersion"
            "fabric" -> "BlockPops - Fabric - $mcVersion"
            "neoforge" -> "BlockPops - Neoforge - $mcVersion"
            else -> "${rootProject.property("archives_name")}-${project.name}"
        })
    }

    repositories {
        maven {
            name = "GeckoLib"
            url = uri("https://dl.cloudsmith.io/public/geckolib3/geckolib/maven/")
            content {
                includeGroupByRegex("software\\.bernie.*")
                includeGroup("com.eliotlash.mclib")
            }
        }
        maven {
            name = "NeoForge"
            url = uri("https://maven.neoforged.net/releases/")
        }
    }

    dependencies {
        "minecraft"("net.minecraft:minecraft:$mcVersion")
        "mappings"(project.extensions.getByName<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom").officialMojangMappings())
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(javaRelease)
    }

    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                artifactId = project.extensions.getByType<BasePluginExtension>().archivesName.get()
                from(components["java"])
            }
        }
        repositories {
        }
    }
}

// Export values for subprojects
extra["mcVersion"] = mcVersion
extra["mcVersionUnderscored"] = mcVersionUnderscored
extra["versionProp"] = { baseName: String -> versionProp(baseName) }
extra["javaVersion"] = javaVersion
extra["javaRelease"] = javaRelease
