plugins {
    id("dev.architectury.loom") version "1.11-SNAPSHOT" apply false
    id("architectury-plugin") version "3.4-SNAPSHOT"
    id("com.gradleup.shadow") version "8.3.6" apply false
}

architectury {
    minecraft = project.property("minecraft_version") as String
}

allprojects {
    group = rootProject.property("maven_group") as String
    version = rootProject.property("mod_version") as String
}

subprojects {
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "architectury-plugin")
    apply(plugin = "maven-publish")
    apply(plugin = "java")

    extensions.configure<BasePluginExtension> {
        // Set up named JARs for each platform
        archivesName.set(when (project.name) {
            "forge" -> "BlockPops - Forge - ${rootProject.property("minecraft_version")}"
            "fabric" -> "BlockPops - Fabric - ${rootProject.property("minecraft_version")}"
            "neoforge" -> "BlockPops - Neoforge - ${rootProject.property("minecraft_version")}"
            else -> "${rootProject.property("archives_name")}-${project.name}"
        })
    }

    repositories {
        // Add repositories to retrieve artifacts from in here.
        // You should only use this when depending on other mods because
        // Loom adds the essential maven repositories to download Minecraft and libraries from automatically.
        // See https://docs.gradle.org/current/userguide/declaring_repositories.html
        // for more information about repositories.
    }

    dependencies {
        "minecraft"("net.minecraft:minecraft:${rootProject.property("minecraft_version")}")
        "mappings"(project.extensions.getByName<net.fabricmc.loom.api.LoomGradleExtensionAPI>("loom").officialMojangMappings())
    }

    extensions.configure<JavaPluginExtension> {
        // Loom will automatically attach sourcesJar to a RemapSourcesJar task and to the "build" task
        // if it is present.
        // If you remove this line, sources will not be generated.
        withSourcesJar()

        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    // Configure Maven publishing.
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("mavenJava") {
                artifactId = project.extensions.getByType<BasePluginExtension>().archivesName.get()
                from(components["java"])
            }
        }

        // See https://docs.gradle.org/current/userguide/publishing_maven.html for information on how to set up publishing.
        repositories {
            // Add repositories to publish to here.
            // Notice: This block does NOT have the same function as the block in the top level.
            // The repositories here will be used for publishing your artifact, not for
            // retrieving dependencies.
        }
    }
}
