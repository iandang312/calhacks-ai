import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Keep build output off OneDrive to avoid Windows file-lock errors.
val localBuildDir = File(System.getProperty("user.home"), ".cache/daisy-sandbox/app/build")
layout.buildDirectory.set(localBuildDir)

// Pull the Deepgram key out of local.properties (gitignored) and into BuildConfig,
// so it is never hardcoded in source. See local.properties.example.
val localProps = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localFile.inputStream().use { localProps.load(it) }
}

android {
    namespace = "com.example.showgraphs"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.showgraphs"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "DEEPGRAM_API_KEY",
            "\"${localProps.getProperty("DEEPGRAM_API_KEY", "")}\"",
        )
        // Deepgram Aura TTS voice model; override in local.properties if desired.
        val ttsModel = localProps.getProperty("DEEPGRAM_TTS_MODEL", "").ifBlank { "aura-asteria-en" }
        buildConfigField("String", "DEEPGRAM_TTS_MODEL", "\"$ttsModel\"")

        // Base URL of the backend intent service (backend/intent_service). From the
        // emulator the host machine is 10.0.2.2; override for a physical device.
        val intentUrl = localProps.getProperty("INTENT_SERVICE_URL", "").ifBlank { "http://10.0.2.2:8000" }
        buildConfigField("String", "INTENT_SERVICE_URL", "\"$intentUrl\"")

        // Base URL of the agent execution service (agent/server.py) that drives the
        // device. The inferred plan is POSTed here as the task. Defaults to port
        // 8001 since the intent service owns 8000 on the same host.
        val agentUrl = localProps.getProperty("AGENT_SERVICE_URL", "").ifBlank { "http://10.0.2.2:8001" }
        buildConfigField("String", "AGENT_SERVICE_URL", "\"$agentUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    // OkHttp provides the WebSocket used for Deepgram real-time streaming STT.
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}
