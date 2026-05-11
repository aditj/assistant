import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(key: String): String =
    localProps.getProperty(key)
        ?: (project.findProperty(key) as? String)
        ?: System.getenv(key)
        ?: ""

android {
    namespace = "com.aditjain.assistant"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aditjain.assistant"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        val backendUrl = (project.findProperty("BACKEND_URL") as String?) ?: "http://10.0.2.2:8000"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
        buildConfigField("String", "API_KEY", "\"${secret("ASSISTANT_API_KEY")}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
