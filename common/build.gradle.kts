plugins {
    id("dev.architectury.loom")
}

@Suppress("UNCHECKED_CAST")
val versionProp = rootProject.extra["versionProp"] as (String) -> String
val mcVersion = rootProject.extra["mcVersion"] as String
val enabledPlatforms = rootProject.property("enabled_platforms").toString().split(",")

architectury {
    common(enabledPlatforms)
}

// Add version-specific source set for GeckoLib-dependent code
// This allows different imports/method signatures per Minecraft version
val versionSourceSet = if (mcVersion.startsWith("1.21")) "v1_21_1" else "v1_20_1"
sourceSets {
    main {
        java {
            srcDir("src/$versionSourceSet/java")
        }
    }
}

dependencies {
    minecraft("net.minecraft:minecraft:$mcVersion")
    mappings(loom.officialMojangMappings())

    // We depend on Fabric Loader here to use the Fabric @Environment annotations,
    // which get remapped to the correct annotations on each platform.
    // Do NOT use other classes from Fabric Loader.
    modImplementation("net.fabricmc:fabric-loader:${versionProp("fabric_loader_version")}")

    // Architectury API
    modImplementation("dev.architectury:architectury:${versionProp("architectury_api_version")}")

    // GeckoLib - common API (platform-specific implementations provided by forge/fabric modules)
    // Use NeoForge version for 1.21+, Forge version for 1.20
    if (mcVersion.startsWith("1.21")) {
        modCompileOnly("software.bernie.geckolib:geckolib-neoforge-$mcVersion:${versionProp("geckolib_version")}")
    } else {
        modCompileOnly("software.bernie.geckolib:geckolib-forge-$mcVersion:${versionProp("geckolib_version")}")
    }
}
