pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "wisteria"

/*
 * One Minecraft version is built at a time, selected with -Pmc=<id> (default below),
 * where <id> names a file in versions/. The ids mirror Super Resolution's configs/ so the
 * two stay easy to compare.
 *
 * Only the loaders a version actually supports are included, which is why 1.20.1 builds
 * Forge while the others build Fabric and NeoForge. The shared module needs no Minecraft
 * at all, so it is identical across versions.
 */
val versionId = providers.gradleProperty("mc").getOrElse("26.1.x")
val versionFile = file("versions/$versionId.properties")
if (!versionFile.isFile) {
    val available = file("versions").listFiles()
        ?.filter { it.name.endsWith(".properties") }
        ?.map { it.name.removeSuffix(".properties") }
        ?.sorted()
        ?: emptyList()
    throw GradleException(
        "Unknown Minecraft version '$versionId'. Use -Pmc=<id> with one of: ${available.joinToString(", ")}"
    )
}

val versionProperties = java.util.Properties().apply {
    versionFile.inputStream().use { load(it) }
}
gradle.extensions.extraProperties["wisteriaVersionId"] = versionId
gradle.extensions.extraProperties["wisteriaVersionProperties"] = versionProperties

println("❇️ Wisteria target: Minecraft ${versionProperties.getProperty("minecraft_version")} ($versionId)")

include("common")
versionProperties.getProperty("loaders")
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .forEach {
        println("❇️ loader $it")
        include(it)
    }
