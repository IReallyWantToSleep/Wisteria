plugins {
    id("net.neoforged.moddev.legacyforge")
}

evaluationDependsOn(":common")

val common = project(":common")
val commonMain = common.extensions.getByType<SourceSetContainer>().named("main").get()

fun cfg(key: String): String = rootProject.extra[key] as String

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
            gameDirectory = rootProject.file("run/forge-1.20.1")
        }
    }
}

dependencies {
    implementation(commonMain.output)
}

tasks.named<Jar>("jar") {
    from(commonMain.output)
}
