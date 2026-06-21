import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Keep build output off OneDrive to avoid Windows file-lock errors.
val localBuildDir = File(System.getProperty("user.home"), ".cache/daisy-demo/app/build")
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
        buildConfigField(
            "String",
            "ELEVENLABS_API_KEY",
            "\"${localProps.getProperty("ELEVENLABS_API_KEY", "")}\"",
        )
        buildConfigField(
            "String",
            "ELEVENLABS_VOICE_ID",
            "\"${localProps.getProperty("ELEVENLABS_VOICE_ID", "21m00Tcm4TlvDq8ikWAM")}\"",
        )
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
    testOptions {
        unitTests {
            isReturnDefaultValues = true
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
    testImplementation(libs.mockwebserver)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

}
