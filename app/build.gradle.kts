import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kover)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Exported schemas are checked in, so a schema change shows up in review and gets a migration.
room {
    schemaDirectory("$projectDir/schemas")
}

// Cloud endpoints (SPEC cloud-sync). Publishable key only, never the service role key. Debug builds default to
// the local stack as the emulator sees it (10.0.2.2); release builds get nothing unless local.properties sets
// gwigaon.release.supabaseUrl / .supabaseKey / .contactPageUrl (T20), and with no URL the app stays offline-only.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cloudSetting(buildType: String, name: String, default: String): String =
    (localProps.getProperty("gwigaon.$buildType.$name") ?: default).replace("\\", "\\\\").replace("\"", "\\\"")

// Release signing (T23): a local keystore named by keystore.properties at the repo root; both are gitignored.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseKeystore: File? = keystoreProps.getProperty("storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Packaging a release fails without the cloud settings (/ship Q5: the APK would quietly run offline-only) or the
// keystore (T23: it would be unsigned and refuse to install). CI has neither and builds release only to check the
// release classpath, so it may package an unsigned, offline APK; that APK is never shipped.
val missingReleaseSetup: List<String> = listOf("supabaseUrl", "supabaseKey", "contactPageUrl")
    .filter { localProps.getProperty("gwigaon.release.$it").isNullOrBlank() }
    .map { "gwigaon.release.$it in local.properties" } +
    listOfNotNull("keystore.properties with an existing storeFile".takeIf { releaseKeystore == null })
val releaseSetupOptional: Boolean = System.getenv("CI") == "true"
val checkReleaseSetup = tasks.register("checkReleaseSetup") {
    val missing = missingReleaseSetup
    val optional = releaseSetupOptional
    doLast {
        if (missing.isNotEmpty() && !optional) {
            throw GradleException("Release build needs ${missing.joinToString()}.")
        }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(checkReleaseSetup)
}

android {
    namespace = "com.coral.gwigaon"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.coral.gwigaon"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "SUPABASE_URL", "\"${cloudSetting("debug", "supabaseUrl", "http://10.0.2.2:54321")}\"")
            // The local stack's fixed publishable key (also in web/contact/config.js); not a secret.
            buildConfigField("String", "SUPABASE_KEY", "\"${cloudSetting("debug", "supabaseKey", "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")}\"")
            buildConfigField("String", "CONTACT_PAGE_URL", "\"${cloudSetting("debug", "contactPageUrl", "http://127.0.0.1:8080/")}\"")
        }
        release {
            buildConfigField("String", "SUPABASE_URL", "\"${cloudSetting("release", "supabaseUrl", "")}\"")
            buildConfigField("String", "SUPABASE_KEY", "\"${cloudSetting("release", "supabaseKey", "")}\"")
            buildConfigField("String", "CONTACT_PAGE_URL", "\"${cloudSetting("release", "contactPageUrl", "")}\"")
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // The exported schemas ride along with the instrumented tests, so a migration test can build an old database.
    sourceSets.getByName("androidTest").assets.srcDir("$projectDir/schemas")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Lock the shipped classpaths so osv-scanner can read app/gradle.lockfile (CONSTRAINTS.md, dependencies).
// After changing a dependency: ./gradlew :app:dependencies --write-locks
configurations.matching { it.name == "debugRuntimeClasspath" || it.name == "releaseRuntimeClasspath" }
    .configureEach { resolutionStrategy.activateDependencyLocking() }
