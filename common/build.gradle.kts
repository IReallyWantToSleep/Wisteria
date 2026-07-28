// No loader here: the shared module talks only to the Super Resolution API, vanilla
// Minecraft, LWJGL and the event bus. Vanilla comes from NeoForm, the same loader-agnostic
// route Super Resolution's own common module takes; it is needed because SR's registration
// API describes backends with Component display names.
plugins {
    `java-library`
    id("net.neoforged.moddev")
}

fun cfg(key: String): String = rootProject.extra[key] as String

neoForge {
    neoFormVersion = cfg("neoform_version")
}

dependencies {
    // Minecraft ships no lwjgl-vulkan, but the NVNGX and Streamline backends name Vulkan
    // constants directly, so the bindings have to be on the compile classpath. Only the
    // bindings: lwjgl core comes from Minecraft, whose own version is pinned strictly and
    // would collide with the one this artifact asks for. slf4j comes from Minecraft too.
    compileOnly("org.lwjgl:lwjgl-vulkan:${cfg("lwjgl_version")}") { isTransitive = false }
    // SuperResolutionAPI.EVENT_BUS is a NeoForge event bus, used on both loaders.
    compileOnly("net.neoforged:bus:8.0.5")
}

/*
 * NVIDIA Streamline runtime.
 *
 * The DLLs are redistributable but not committable, so they are copied out of a local SDK
 * checkout at build time and only ever exist inside the jar. Point somewhere else with
 * -Pstreamline_bin_dir=<path>. Only the release variant ships; for the debug variant, aim
 * the property at the SDK's development/ subdirectory.
 */
val streamlineBinDir = file(
    providers.gradleProperty("streamline_bin_dir").orElse("K:/sl/bin/x64").get()
)
val streamlineResourceDir = layout.projectDirectory.dir("src/main/resources/streamline")
val streamlineLibraries = listOf(
    "NvLowLatencyVk.dll",
    "nvngx_dlssg.dll",
    "sl.common.dll",
    "sl.dlss_g.dll",
    "sl.interposer.dll",
    "sl.pcl.dll",
    "sl.reflex.dll"
)

val syncStreamlineLibraries = tasks.register<Sync>("syncStreamlineLibraries") {
    group = "build"
    description = "Copies the Streamline runtime into the jar resources"

    from(streamlineBinDir) { include(streamlineLibraries) }
    into(streamlineResourceDir)

    doFirst {
        val missing = streamlineLibraries.filterNot { streamlineBinDir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Streamline SDK is incomplete at ${streamlineBinDir.absolutePath}; missing: "
                    + missing.joinToString()
            )
        }
    }

    // StreamlineNativeExtractor cannot list a directory inside a jar, so the names travel
    // with the DLLs. The sizes let it skip files it has already written.
    doLast {
        streamlineResourceDir.file("index.txt").asFile.writeText(
            streamlineLibraries.joinToString("\n", postfix = "\n") {
                "$it ${streamlineBinDir.resolve(it).length()}"
            }
        )
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(syncStreamlineLibraries)
}
