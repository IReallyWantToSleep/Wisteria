evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

val unobfuscated = cfg("unobfuscated").toBoolean()

// Versions before 26.1 are obfuscated and need the remapping variant of Loom; applying it
// by id keeps one build script working for every version.
apply(plugin = if (unobfuscated) "net.fabricmc.fabric-loom" else "net.fabricmc.fabric-loom-remap")

val loom = extensions.getByType<net.fabricmc.loom.api.LoomGradleExtensionAPI>()

/**
 * Loom only creates the remapping (`mod*`) configurations when Minecraft is obfuscated;
 * on 26.1 and later they are absent and plain `implementation` is correct. Resolving the
 * name keeps this working on every supported version.
 */
fun modImplementationName(): String =
    listOf("modImplementation", "implementation").first { configurations.findByName(it) != null }

fun modRuntimeOnlyName(): String =
    listOf("modRuntimeOnly", "runtimeOnly").first { configurations.findByName(it) != null }

data class RuntimeModrinthDependency(
    val name: String,
    val version: String,
    val minecraftVersion: String?
)

fun runtimeModrinthDependencies(): List<RuntimeModrinthDependency> {
    val prefix = "fabric_runtime_modrinth."
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

fun fabricModrinthNotation(dependency: RuntimeModrinthDependency): String {
    val minecraftVersion = dependency.minecraftVersion ?: cfg("minecraft_version")
    return if ((dependency.name == "sodium" && usesCaffeineSodium())
        || dependency.name == "sodium.maven"
    ) {
        "net.caffeinemc:sodium-fabric:${dependency.version}"
    } else {
        "maven.modrinth:${dependency.name}:${dependency.version}-fabric,$minecraftVersion"
    }
}

fun superResolutionModJar(): File? {
    val srModDir = rootProject.file("sr_mod")
    val prefix = "super_resolution-fabric-${cfg("sr_artifact_mc")}-"
    val variant = providers.gradleProperty("sr_mod_variant").orElse("opengl").get()
    val candidates = srModDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".$variant.jar") }
        ?.sortedBy { it.name }
        ?: emptyList()
    if (candidates.size > 1) {
        throw GradleException(
            "Expected exactly one Fabric Super Resolution jar matching "
                + "$prefix*.$variant.jar in ${srModDir.absolutePath}, found: "
                + candidates.joinToString { it.name }.ifBlank { "<none>" }
        )
    }
    return candidates.singleOrNull()
}

fun superResolutionModrinthNotation(): String =
    "maven.modrinth:${cfg("sr_modrinth_project_id")}:${cfg("sr_modrinth_version_id_fabric")}"

// Loom's configurations are addressed by name: it registers them too late in the Kotlin
// DSL accessor pass for the generated typed accessors to exist.
dependencies {
    "minecraft"("com.mojang:minecraft:${cfg("minecraft_version")}")
    // Unobfuscated Minecraft (26.1+) needs no mappings, and Loom rejects Mojang mappings
    // outright in a non-obfuscated environment.
    if (!unobfuscated) {
        "mappings"(loom.officialMojangMappings())
    }
    add(modImplementationName(), "net.fabricmc:fabric-loader:${cfg("fabric_loader_version")}")
    add(modImplementationName(), "net.fabricmc.fabric-api:fabric-api:${cfg("fabric_api_version")}")
    val localSuperResolutionMod = superResolutionModJar()
    if (localSuperResolutionMod != null) {
        add(modRuntimeOnlyName(), files(localSuperResolutionMod))
    } else {
        add(modRuntimeOnlyName(), superResolutionModrinthNotation())
    }

    for (dependency in runtimeModrinthDependencies()) {
        add(modImplementationName(), fabricModrinthNotation(dependency))
    }

    // Keep the shared output on the development runtime classpath as well. The loader jar
    // bundles it for distribution, but Loom's runClient uses classes directories directly.
    implementation(commonMain.output)
}

// Bundle the shared module's classes and resources into the loader jar.
tasks.named<Jar>("jar") {
    from(commonMain.output)
}
