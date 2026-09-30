plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

subprojects {
    apply(
        plugin =
            rootProject.libs.plugins.ktlint
                .get()
                .pluginId,
    )
    apply(
        plugin =
            rootProject.libs.plugins.detekt
                .get()
                .pluginId,
    )

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set(
            rootProject.libs.versions.ktlintTool
                .get(),
        )
        // Generated Room and Compose sources are not ours to format.
        filter {
            exclude { it.file.path.contains("/build/") }
        }
    }

    configure<dev.detekt.gradle.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
    }

    // No jvmTarget or classpath wiring here any more. detekt 2 registers analysis tasks per
    // source set and per compilation and reads that information itself, so the hand-wiring
    // that 1.x needed is not just redundant but the thing the migration guide asks you to
    // delete.
    tasks.withType<dev.detekt.gradle.Detekt>().configureEach {
        reports {
            html.required.set(true)
            sarif.required.set(true)
            // `xml` is `checkstyle` in 2.x and `txt` is gone entirely.
            checkstyle.required.set(false)
        }
    }
}

/** One command for everything CI checks, so `check` locally means the same thing. */
tasks.register("staticAnalysis") {
    group = "verification"
    description = "Runs ktlint, detekt and Android lint across every module."
    dependsOn(subprojects.map { "${it.path}:ktlintCheck" })

    // detektMain and detektTest, not the plain `detekt` task.
    //
    // detekt 2 registers analysis tasks per source set and per compilation, and those are
    // the ones that resolve types. The bare `detekt` task is the retiring 1.x-shaped one:
    // on this project it finishes in four seconds and reports nothing, where detektMain
    // takes two minutes and found six real issues on its first run. Depending on the fast
    // one would have turned this upgrade into a silent loss of coverage.
    dependsOn(":app:detektMain", ":app:detektTest")

    // Android lint belongs here too. It was missing, and because this task is documented
    // as "what CI runs", a green run locally read as a green build - while lintDebug,
    // which CI runs separately and with warningsAsErrors, was never invoked at all. It
    // caught a real error in test code that had already been pushed.
    //
    // Named explicitly rather than mapped over subprojects: detekt's variant tasks and
    // lint both exist only on Android modules, so the mapped form above would break on a
    // plain Kotlin one.
    dependsOn(":app:lintDebug")
}
