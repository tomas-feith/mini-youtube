import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

/**
 * Local signing credentials, absent on CI and on a fresh clone.
 *
 * The file is gitignored and points at a keystore stored outside the repository. Android
 * identifies an installed app by applicationId plus signing certificate, so losing this
 * key means the app can never be updated in place again. See docs/INSTALLING.md.
 */
val keystoreProperties =
    rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
        Properties().apply { file.inputStream().use { load(it) } }
    }

plugins {
    // AGP 9 applies the Kotlin Android plugin itself; applying it here as well fails.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.miniyoutube.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.miniyoutube.app"
        resValue("string", "app_name", "Mini YouTube")
        minSdk = 26
        targetSdk = 37
        // Bump on anything that gets installed on a real phone. The app is sideloaded, so
        // the version is the only thing that says which build is installed.
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Only declared when the credentials are present. On CI and on a fresh clone the
        // release build simply comes out unsigned, which is correct: an unsigned artifact
        // is obviously unusable, whereas one silently signed with the debug key looks fine
        // and then cannot be updated by a real release later.
        keystoreProperties?.let { props ->
            create("release") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // A separate application id, so a debug build - including the one
            // `connectedAndroidTest` installs - can never collide with the release install
            // and force an uninstall that would take the followed channels with it.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "Mini YouTube (debug)")
        }
        release {
            signingConfig = signingConfigs.findByName("release")

            // Left off for now: an unminified release is one fewer variable if a
            // release-only failure ever appears. Turning it on later needs the Room,
            // Compose and player keep rules verified on device, not just a green build.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // AGP 9 turns custom resource values off by default. app_name is declared per build
        // type so the debug install is labelled distinctly on the launcher.
        resValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        // A personal app with no release train, so a warning that never gets triaged is
        // just noise. Fail the build instead.
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        disable +=
            setOf(
                // Version bumps are Dependabot's job, not a build failure's.
                "GradleDependency",
                "NewerVersionAvailable",
                "AndroidGradlePluginVersion",
            )
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// The migration test replays the committed schema JSONs, so they have to be on the
// instrumentation test classpath as assets.
android.sourceSets
    .getByName("androidTest")
    .assets
    .srcDir("$projectDir/schemas")

// AGP 9 dropped the `kotlinOptions` block in favour of the Kotlin plugin's own DSL.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Room's runtime pulls kotlinx-serialization 1.7.3, while room-testing needs 1.9.0.
    // AGP's consistent resolution pins the test classpath to the app's version, so without
    // this the instrumentation tests cannot resolve at all. Aligning the app on the BOM
    // gives both classpaths the same serialization.
    implementation(platform(libs.kotlinx.serialization.bom))

    implementation(libs.kotlinx.coroutines.android)

    // The periodic feed check that posts the "new video" notification.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // Plain OkHttp rather than Retrofit: the app fetches an Atom feed, a channel page and
    // an oEmbed document, none of which is a REST API a declarative interface would fit.
    implementation(libs.okhttp)

    // Thumbnails and channel avatars, sharing the app's OkHttp client.
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // In-app playback through YouTube's own IFrame player in a WebView - the embed YouTube
    // sanctions for third-party apps. It sets the HTTP referrer the embed has required
    // since mid-2025, which a hand-rolled WebView page would have to get right itself.
    implementation(libs.youtube.player.core)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Exercises the wire handling over a real socket, without touching YouTube.
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // Replays committed schema JSONs so migrations are verified, not assumed.
    androidTestImplementation(libs.androidx.room.testing)
    // Room's MigrationTestHelper parses those JSONs with kotlinx-serialization, and a
    // core/json version mismatch surfaces only at runtime as AbstractMethodError.
    androidTestImplementation(platform(libs.kotlinx.serialization.bom))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
