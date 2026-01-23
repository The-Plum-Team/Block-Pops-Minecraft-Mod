architectury {
    common(rootProject.property("enabled_platforms").toString().split(","))
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
}

dependencies {
    // We depend on Fabric Loader here to use the Fabric @Environment annotations,
    // which get remapped to the correct annotations on each platform.
    // Do NOT use other classes from Fabric Loader.
    modImplementation("net.fabricmc:fabric-loader:${rootProject.property("fabric_loader_version")}")

    // Architectury API. This is optional, and you can comment it out if you don't need it.
    modImplementation("dev.architectury:architectury:${rootProject.property("architectury_api_version")}")

    // GeckoLib - common API (platform-specific implementations provided by forge/fabric modules)
    modCompileOnly("software.bernie.geckolib:geckolib-forge-1.20.1:${rootProject.property("geckolib_version")}")
}
