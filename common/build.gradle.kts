// No Minecraft here. The shared module talks only to the Super Resolution API, LWJGL and
// the event bus, so it needs no loader, and no ModDevGradle/NeoForm decompile. That is
// what lets a single build of this module serve every supported Minecraft version.
plugins {
    `java-library`
}

fun cfg(key: String): String = rootProject.extra[key] as String

dependencies {
    // Provided by Minecraft at runtime on every supported version; only Logger and
    // LoggerFactory are used, which have been stable across the 2.0 line.
    compileOnly("org.slf4j:slf4j-api:2.0.16")
    // Minecraft ships no lwjgl-vulkan; Super Resolution forces its own, and the NVNGX
    // backend records Vulkan commands directly, so it must be on the compile classpath.
    compileOnly("org.lwjgl:lwjgl-vulkan:${cfg("lwjgl_version")}")
    // SuperResolutionAPI.EVENT_BUS is a NeoForge event bus, used on both loaders.
    compileOnly("net.neoforged:bus:8.0.5")
}
