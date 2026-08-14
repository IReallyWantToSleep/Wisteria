plugins {
    id("net.neoforged.moddev.legacyforge")
}

evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

fun superResolutionModJar(): File? {
    val srModDir = rootProject.file("sr_mod")
    val prefix = "super_resolution-forge-${cfg("sr_artifact_mc")}-"
    val variant = providers.gradleProperty("sr_mod_variant").orElse("opengl").get()
    val candidates = srModDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".$variant.jar") }
        ?.sortedBy { it.name }
        ?: emptyList()
    if (candidates.size > 1) {
        throw GradleException(
            "Expected exactly one Forge Super Resolution jar matching "
                + "$prefix*.$variant.jar in ${srModDir.absolutePath}, found: "
                + candidates.joinToString { it.name }.ifBlank { "<none>" }
        )
    }
    return candidates.singleOrNull()
}

fun superResolutionModrinthNotation(): String =
    "maven.modrinth:${cfg("sr_modrinth_project_id")}:${cfg("sr_modrinth_version_id_forge")}"

legacyForge {
    version = "${cfg("minecraft_version")}-${cfg("forge_version")}"

    mods {
        register(providers.gradleProperty("mod_id").get()) {
            sourceSet(sourceSets.named("main").get())
        }
    }

    runs {
        register("client") {
            client()
            gameDirectory = rootProject.file("run/forge")
        }
    }
}

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    annotationProcessor("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    implementation(commonMain.output)
    val localSuperResolutionMod = superResolutionModJar()
    if (localSuperResolutionMod != null) {
        modRuntimeOnly(files(localSuperResolutionMod))
    } else {
        modRuntimeOnly(superResolutionModrinthNotation())
    }
}

tasks.named<Jar>("jar") {
    from(commonMain.output)
}

