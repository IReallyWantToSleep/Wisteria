plugins {
    id("net.neoforged.moddev")
}

evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

data class RuntimeModrinthDependency(
    val name: String,
    val version: String,
    val minecraftVersion: String?
)

fun runtimeModrinthDependencies(): List<RuntimeModrinthDependency> {
    val prefix = "neoforge_runtime_modrinth."
    return rootProject.extra.properties.keys
        .filter { it.startsWith(prefix) }
        .sorted()
        .map { key ->
            val value = rootProject.extra.properties[key]?.toString().orEmpty()
            val parts = value.split("|", limit = 2)
            require(parts.firstOrNull()?.isNotBlank() == true) {
                "Invalid runtime dependency '$key' in versions/${rootProject.extra["wisteriaVersionId"]}.properties"
            }
            RuntimeModrinthDependency(
                name = key.removePrefix(prefix),
                version = parts[0],
                minecraftVersion = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            )
        }
}

fun usesCaffeineSodium(): Boolean {
    val minecraftVersion = cfg("minecraft_version")
    if (minecraftVersion.startsWith("26.")) {
        return true
    }
    val minor = Regex("""^1\.21\.(\d+)$""").matchEntire(minecraftVersion)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
    return minor != null && minor >= 11
}

fun neoForgeModrinthNotations(dependency: RuntimeModrinthDependency): List<String> {
    val minecraftVersion = dependency.minecraftVersion ?: cfg("minecraft_version")
    if ((dependency.name == "sodium" && usesCaffeineSodium())
        || dependency.name == "sodium.maven"
    ) {
        return listOf(
            "net.caffeinemc:sodium-neoforge-mod:${dependency.version}",
            "net.caffeinemc:sodium-neoforge:${dependency.version}"
        )
    }
    return listOf("maven.modrinth:${dependency.name}:${dependency.version}-neoforge,$minecraftVersion")
}

fun superResolutionModJar(): File {
    val srModDir = rootProject.file("sr_mod")
    val prefix = "super_resolution-neoforge-${cfg("sr_artifact_mc")}-"
    val variant = providers.gradleProperty("sr_mod_variant").orElse("opengl").get()
    val candidates = srModDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".$variant.jar") }
        ?.sortedBy { it.name }
        ?: emptyList()
    if (candidates.size != 1) {
        throw GradleException(
            "Expected exactly one NeoForge Super Resolution jar matching "
                + "$prefix*.$variant.jar in ${srModDir.absolutePath}, found: "
                + candidates.joinToString { it.name }.ifBlank { "<none>" }
        )
    }
    return candidates.single()
}

neoForge {
    version = cfg("neoforge_version")

    mods {
        register(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.named("main").get())
        }
    }

    runs {
        register("client") {
            client()
        }
    }
}

dependencies {
    implementation(files(superResolutionModJar()))

    for (dependency in runtimeModrinthDependencies()) {
        neoForgeModrinthNotations(dependency).forEach { implementation(it) }
    }

    // Keep the shared output on the development runtime classpath as well. The loader jar
    // bundles it for distribution, but ModDev's runClient uses classes directories directly.
    implementation(commonMain.output)
}

// Bundle the shared module's classes and resources into the loader jar.
tasks.named<Jar>("jar") {
    from(commonMain.output)
}
