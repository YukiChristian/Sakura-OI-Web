plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 69
        versionName = "0.9.1.7$sakuraVersionSuffix"
        applicationIdSuffix = sakuraApplicationIdSuffix

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones: 64- and 32-bit ARM. Debug builds add x86_64 for the emulator (see below); the
        // published APK leaves out that 9 MB copy of the speech library, which no phone uses.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
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
        getByName("debug") {
            if (sakuraKeystoreFile.isNotEmpty()) signingConfig = signingConfigs.getByName("sakura")
            ndk { abiFilters += "x86_64" }
        }
        release {
            if (sakuraKeystoreFile.isNotEmpty()) signingConfig = signingConfigs.getByName("sakura")
            // R8 over the whole app (code and unused resources). Optimizing only androidx/kotlin broke
            // start-up; the whole program optimized together does not. proguard-rules.pro keeps what
            // is reached by name: the JavaScript bridges and the speech library (Vosk/JNA).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // The speech library goes compressed in the APK (about 8.6 MB to 2.8 MB per copy); Android
        // unpacks the one copy the phone needs when it installs the app.
        jniLibs { useLegacyPackaging = true }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    // Offline speech recognition for the optional "Oye, Sakura" wake phrase.
    implementation(libs.vosk.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
