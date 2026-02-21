plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.animus.aireplyassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.animus.aireplyassistant"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        // When empty, the app uses the mock backend.
        buildConfigField("String", "REPLY_API_BASE_URL", "\"http://192.168.31.136:4000\"")

        // ARM-only universal APK (no ABI splits). Includes arm64-v8a explicitly.
        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    splits {
        abi {
            isEnable = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.savedstate)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)

    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    debugImplementation(platform(libs.compose.bom))
    debugImplementation(libs.compose.ui.tooling)
}
