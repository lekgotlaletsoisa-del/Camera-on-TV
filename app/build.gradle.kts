plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "za.co.cameraontv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "za.co.cameraontv"
        minSdk = 23
        targetSdk = 37
        versionCode = 4
        versionName = "1.3"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.nanohttpd)
}
