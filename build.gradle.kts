import java.util.Properties
import net.neoforged.nfrtgradle.CreateMinecraftArtifacts

plugins {
    // Applied by the loader modules; declared here so its types are on the buildscript
    // classpath and the version is pinned in one place. The root project holds no
    // sources and produces no artifact.
    id("net.neoforged.moddev") version "2.0.141" apply false
    id("net.neoforged.moddev.legacyforge") version "2.0.141" apply false
    // Loom ships as two plugins: the plain one for unobfuscated Minecraft (26.1+) and the
    // remap one for older, obfuscated versions. Both are put on the buildscript classpath
    // here so the fabric module can apply whichever the selected version needs, rather
    // than rewriting its build script the way Super Resolution does.
    id("net.fabricmc.fabric-loom") version "1.16.3" apply false
    id("net.fabricmc.fabric-loom-remap") version "1.16.3" apply false
}

val versionProperties = gradle.extensions.extraProperties["wisteriaVersionProperties"] as java.util.Properties
val versionId = gradle.extensions.extraProperties["wisteriaVersionId"] as String

/** Version-specific settings win; anything shared falls back to gradle.properties. */
fun cfg(key: String): String = versionProperties.getProperty(key)
    ?: providers.gradleProperty(key).orNull
    ?: throw GradleException("Missing '$key' in versions/$versionId.properties or gradle.properties")

/**
 * The Super Resolution API artifact Wisteria compiles against.
 *
 * The default Maven coordinate serves the modern Minecraft versions. A version file may
 * override sr_version when that target needs a different bytecode level, such as the
 * Java 17 API artifact used by Minecraft 1.20.1.
 *
 * To work against unpublished API changes, run `publishToMavenLocal` in the Super
 * Resolution project and set `sr_version` to that build - mavenLocal() is searched first.
 */
fun superResolutionApi(): String {
    val srVersion = cfg("sr_version").takeIf { it.isNotBlank() }
        ?: throw GradleException(
            "sr_version is not set. Point it at a published Super Resolution API version, "
                + "or publish one locally with `./gradlew :common:publishApiPublicationToMavenLocal "
                + "-Pminecraft_version_config=1.20.1` in the Super Resolution project."
        )
    return "${cfg("sr_group")}:${cfg("sr_api_module")}:$srVersion"
}

allprojects {
    group = providers.gradleProperty("group").get()
    version = "${providers.gradleProperty("mod_version").get()}+${versionProperties.getProperty("minecraft_version")}"

    // Version-specific settings are readable from every module's build script.
    versionProperties.stringPropertyNames().forEach { extra.set(it, versionProperties.getProperty(it)) }
    extra.set("wisteriaVersionId", versionId)

    repositories {
        mavenLocal()
        mavenCentral()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.neoforged.net/releases")
        maven("https://libraries.minecraft.net")
        maven("https://api.modrinth.com/maven") {
            content { includeGroup("maven.modrinth") }
        }
        maven("https://maven.caffeinemc.net/releases") {
            content { includeGroup("net.caffeinemc") }
        }
        maven("https://maven.caffeinemc.net/snapshots") {
            content { includeGroup("net.caffeinemc") }
        }
        // Super Resolution's API artifact. Development builds are published as -SNAPSHOT,
        // so point at the snapshot repository; reads are anonymous, no credentials needed.
        maven("https://nexus.nyat.icu/repository/maven-snapshots/") {
            content { includeGroup("io.homo.superresolution") }
            mavenContent { snapshotsOnly() }
        }
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(cfg("java_version").toInt()))
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(cfg("java_version").toInt())
    }

    // wisteria-fabric-26.1-<version>.jar rather than fabric-<version>.jar. This covers
    // AbstractArchiveTask, not just Jar, because on obfuscated versions the shipped
    // artifact is Loom's remapJar.
    tasks.withType<AbstractArchiveTask>().configureEach {
        archiveBaseName.set("${providers.gradleProperty("mod_id").get()}-${project.name}-${cfg("artifact_mc")}")
    }

    tasks.withType<ProcessResources>().configureEach {
        val expansions = mapOf(
            "mod_id" to providers.gradleProperty("mod_id").get(),
            "mod_name" to providers.gradleProperty("mod_name").get(),
            "mod_version" to project.version.toString(),
            "mod_license" to providers.gradleProperty("mod_license").get(),
            "mod_authors" to providers.gradleProperty("mod_authors").get(),
            "sr_mod_id" to providers.gradleProperty("sr_mod_id").get(),
            "java_version" to cfg("java_version"),
            "minecraft_version" to cfg("minecraft_version"),
            "fabric_mc_range" to (versionProperties.getProperty("fabric_mc_range") ?: ""),
            "neoforge_mc_range" to (versionProperties.getProperty("neoforge_mc_range") ?: ""),
            "forge_mc_range" to (versionProperties.getProperty("forge_mc_range") ?: ""),
            "forge_loader_range" to (versionProperties.getProperty("forge_loader_range") ?: ""),
            "fabric_loader_version" to (versionProperties.getProperty("fabric_loader_version") ?: "")
        )
        inputs.properties(expansions)
        filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
            expand(expansions)
        }
    }

    // Super Resolution is provided at runtime by the SR mod itself, so it is never
    // bundled: compile-time only, on every module.
    dependencies {
        "compileOnly"(superResolutionApi())
    }

    // On Linux, NeoForm's decompile step spawns Vineflower with only -Xmx4G, which OOMs
    // on modern Minecraft. Route the tool JVM through a wrapper that raises the heap.
    // ModDevGradle sets `toolsJavaExecutable` from its tools toolchain during task
    // registration, so this must be a `configure` action to win the last write.
    if (System.getProperty("os.name").contains("linux", ignoreCase = true)) {
        val toolWrapper = rootProject.file("script/java_tool_wrapper.sh")
        listOf("net.neoforged.moddev", "net.neoforged.moddev.legacyforge").forEach { pluginId ->
            plugins.withId(pluginId) {
                afterEvaluate {
                    tasks.withType(CreateMinecraftArtifacts::class.java).configureEach {
                        toolsJavaExecutable.set(toolWrapper.absolutePath)
                    }
                }
            }
        }
    }
}

/** Resolves the API artifact, so a bad version or an unreachable repository is reported here. */
tasks.register("checkSuperResolutionApi") {
    group = "verification"
    description = "Resolves the Super Resolution API artifact and reports what was found"
    doLast {
        val coordinate = superResolutionApi()
        val resolved = configurations
            .detachedConfiguration(dependencies.create(coordinate))
            .apply { isTransitive = false }
            .resolve()
        println("Super Resolution API: $coordinate")
        resolved.forEach { println("  -> ${it.name} (${it.length() / 1024} KB)") }
    }
}

/*
 * Multi-version build.
 *
 * The target version is read in settings.gradle.kts, which decides what to include, so a
 * single invocation can only ever build one version. Building all of them means nested
 * builds - one per file in versions/ - each collecting its loader jars into build_jars/.
 * They share this project directory, so they are chained to run one at a time rather than
 * in parallel.
 */
val jarOutputDir = layout.projectDirectory.dir("build_jars")

val allVersionIds = file("versions").listFiles()
    ?.filter { it.name.endsWith(".properties") }
    ?.map { it.name.removeSuffix(".properties") }
    ?.sorted()
    ?: emptyList()

fun versionPropertyOf(id: String, key: String): String? {
    val file = file("versions/$id.properties")
    if (!file.isFile) return null
    return Properties().apply { file.inputStream().use { load(it) } }.getProperty(key)
}

tasks.register<Delete>("cleanBuildJars") {
    group = "build"
    description = "Removes previously collected mod jars"
    delete(jarOutputDir)
}

val collectTasks = mutableListOf<String>()
val nestedBuildTasks = mutableListOf<String>()

allVersionIds.forEach { id ->
    val suffix = id.replace(Regex("[^A-Za-z0-9_]"), "_")
    val artifactMc = versionPropertyOf(id, "artifact_mc") ?: id
    val loaders = (versionPropertyOf(id, "loaders") ?: "")
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val nestedBuild = "buildVersion_$suffix"
    val collect = "collectVersion_$suffix"

    tasks.register<GradleBuild>(nestedBuild) {
        group = "build"
        description = "Builds Minecraft $id"
        buildName = "wisteria_$suffix"
        dir = rootDir
        // Clean first: every version writes into the same module build directories, and
        // stale jars from a previous version would otherwise be collected again.
        setTasks(listOf("clean", "build"))
        startParameter.projectProperties["mc"] = id
    }

    tasks.register<Copy>(collect) {
        group = "build"
        description = "Collects the Minecraft $id mod jars into build_jars"
        dependsOn(nestedBuild)
        loaders.forEach { loader ->
            from("$loader/build/libs") {
                // Loader jars only; the shared module's jar is not a loadable mod.
                include("${providers.gradleProperty("mod_id").get()}-$loader-$artifactMc-*.jar")
                exclude("*-sources.jar", "*-javadoc.jar")
            }
        }
        into(jarOutputDir)
    }

    nestedBuildTasks += nestedBuild
    collectTasks += collect
}

// Serialise the nested builds; they all use this same directory.
for (i in 1 until nestedBuildTasks.size) {
    tasks.named(nestedBuildTasks[i]) { mustRunAfter(nestedBuildTasks[i - 1]) }
}
for (i in 1 until collectTasks.size) {
    tasks.named(collectTasks[i]) { mustRunAfter(collectTasks[i - 1]) }
}

// Wired out here: mutating other tasks from inside a task's configuration action is not
// allowed.
collectTasks.forEach { collect ->
    tasks.named(collect) { mustRunAfter("cleanBuildJars") }
}

tasks.register("buildAllVersions") {
    group = "build"
    description = "Builds every supported Minecraft version and collects the jars into build_jars"
    dependsOn("cleanBuildJars")
    dependsOn(collectTasks)

    doLast {
        println("\nBuilt ${allVersionIds.size} versions into ${jarOutputDir.asFile}")
        jarOutputDir.asFile.listFiles()?.sortedBy { it.name }?.forEach {
            println("  ${it.name} (${it.length() / 1024} KB)")
        }
    }
}
