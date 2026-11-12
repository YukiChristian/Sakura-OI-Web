plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Server addresses are optional at build time: without them the app opens its setup
// assistant on first launch, and they can be changed later in the app's settings.
// SAKURA_WEBUI_URL is the public (exterior) address, SAKURA_WEBUI_LAN_URL the home one.
val sakuraWebUiUrl = providers.gradleProperty("SAKURA_WEBUI_URL")
    .orElse("")
    .get()
val sakuraWebUiLanUrl = providers.gradleProperty("SAKURA_WEBUI_LAN_URL")
    .orElse("")
    .get()

// Optional tag appended to the version name of personal builds (for example "c" -> 0.6.0c).
val sakuraVersionSuffix = providers.gradleProperty("SAKURA_VERSION_SUFFIX")
    .orElse("")
    .get()

// Optional application ID suffix to install a test build next to the regular app.
val sakuraApplicationIdSuffix = providers.gradleProperty("SAKURA_APPLICATION_ID_SUFFIX")
    .orElse("")
    .get()

// Signing key for the APK published on GitHub Releases, kept outside the repository: set
// SAKURA_KEYSTORE_FILE, SAKURA_KEYSTORE_PASSWORD, SAKURA_KEY_ALIAS and SAKURA_KEY_PASSWORD in
// ~/.gradle/gradle.properties. Debug builds use the same key when it is set, so a personal build
// and the published one install over each other.
val sakuraKeystoreFile = providers.gradleProperty("SAKURA_KEYSTORE_FILE").orElse("").get()

android {
    namespace = "com.sakura.webapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.sakura.webapp"
        minSdk = 23
        targetSdk = 36
        buildConfigField("String", "WEBUI_URL", "\"$sakuraWebUiUrl\"")
        buildConfigField("String", "WEBUI_LAN_URL", "\"$sakuraWebUiLanUrl\"")
        versionCode = 50
        versionName = "0.8.0$sakuraVersionSuffix"
        applicationIdSuffix = sakuraApplicationIdSuffix

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones (64- and 32-bit ARM) and the x86_64 emulator; skips the rarely used 32-bit x86
        // copy of the speech recognition library to keep the APK smaller.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (sakuraKeystoreFile.isNotEmpty()) {
            create("sakura") {
                storeFile = file(sakuraKeystoreFile)
                storePassword = providers.gradleProperty("SAKURA_KEYSTORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("SAKURA_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("SAKURA_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        if (sakuraKeystoreFile.isNotEmpty()) {
            getByName("debug") { signingConfig = signingConfigs.getByName("sakura") }
        }
        release {
            if (sakuraKeystoreFile.isNotEmpty()) signingConfig = signingConfigs.getByName("sakura")
            // No R8 for now: optimizing only androidx/kotlin made app classes lose access to the
            // optimized Kotlin classes (IllegalAccessError at start-up). The release is the same code
            // as the debug builds that are tested, signed and not debuggable.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.swiperefreshlayout)
    // Offline speech recognition for the optional "Oye, Sakura" wake phrase.
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
