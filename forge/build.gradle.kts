plugins {
    id("net.neoforged.moddev.legacyforge")
}

evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

fun superResolutionModJar(): File {
    val srModDir = rootProject.file("sr_mod")
    val prefix = "super_resolution-forge-${cfg("sr_artifact_mc")}-"
    val variant = providers.gradleProperty("sr_mod_variant").orElse("opengl").get()
    val candidates = srModDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".$variant.jar") }
        ?.sortedBy { it.name }
        ?: emptyList()
    if (candidates.size != 1) {
        throw GradleException(
            "Expected exactly one Forge Super Resolution jar matching "
                    + "$prefix*.$variant.jar in ${srModDir.absolutePath}, found: "
                    + candidates.joinToString { it.name }.ifBlank { "<none>" }
        )
    }
    return candidates.single()
}

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

repositories {
    flatDir {
        dirs("../sr_mod")
    }
}

dependencies {
    compileOnly("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    annotationProcessor("net.fabricmc:sponge-mixin:0.15.2+mixin.0.8.7")
    implementation(commonMain.output)
    modImplementation(mapOf("name" to superResolutionModJar().name.replace(".jar",""),"ext" to "jar"))
}

// Keep the full mod out of Gradle dependency resolution. Forge discovers production
// mods from the game directory, so only runClient needs the local SR jar.
//val prepareSuperResolutionMod = tasks.register<Copy>("prepareSuperResolutionMod") {
//    //from(superResolutionModJar)
//    //into(rootProject.layout.projectDirectory.dir("run/forge/mods"))
//    //rename { "super_resolution-dev.jar" }
//}

tasks.named("runClient") {
    //dependsOn(prepareSuperResolutionMod)
}

tasks.named<Jar>("jar") {
    from(commonMain.output)
}
