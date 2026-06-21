plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

import java.util.Properties

// Keep build output off OneDrive to avoid Windows file-lock errors.
val localBuildDir = File(System.getProperty("user.home"), ".cache/daisy-demo/app/build")
layout.buildDirectory.set(localBuildDir)

// Pull API keys out of local.properties (gitignored) and into BuildConfig so they
// are never hardcoded in source. See local.properties.example.
val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}
fun prop(name: String): String = localProps.getProperty(name, "")

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

        buildConfigField("String", "DEEPGRAM_API_KEY", "\"${prop("DEEPGRAM_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${prop("ELEVENLABS_API_KEY")}\"")
        // Default ElevenLabs voice ("Rachel"); override in local.properties if desired.
        val voiceId = prop("ELEVENLABS_VOICE_ID").ifBlank { "21m00Tcm4TlvDq8ikWAM" }
        buildConfigField("String", "ELEVENLABS_VOICE_ID", "\"$voiceId\"")
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
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}
