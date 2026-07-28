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

    // Shared code is compiled here and bundled into the jar below, not published.
    compileOnly(commonMain.output)
}

// Bundle the shared module's classes and resources into the loader jar.
tasks.named<Jar>("jar") {
    from(commonMain.output)
}

// Super Resolution must be present at runtime: drop an SR jar into run/mods.
