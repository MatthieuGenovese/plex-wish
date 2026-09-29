plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.plexwish.spike"
    compileSdk = 35

    defaultConfig {
        applicationId = "fr.plexwish.spike"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-spike"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Media3 = ExoPlayer. Pas d'extension ffmpeg : on veut justement mesurer
    // ce que le téléphone décode tout seul (voir docs/SPIKE.md).
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
}
