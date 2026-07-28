plugins {
    id("net.neoforged.moddev")
}

evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

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
    // Shared code is compiled here and bundled into the jar below, not published.
    compileOnly(commonMain.output)
}

// Bundle the shared module's classes and resources into the loader jar.
tasks.named<Jar>("jar") {
    from(commonMain.output)
}

// Super Resolution must be present at runtime: place an SR NeoForge jar in run/mods.
