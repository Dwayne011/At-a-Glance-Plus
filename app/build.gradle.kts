plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ataglanceplus"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "com.ataglanceplus"
        // Pixel only, Android 16 or newer: the native At a Glance slot honours RemoteViews
        // (custom layouts) from Android 15 QPR2 / Android 16, and that is the whole point.
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 61
        versionName = "0.22.2"

        // Pixel phones are arm64 only; this keeps Conscrypt and the pairing library small.
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        prefab = true
    }

    packaging {
        resources {
            // BouncyCastle's modules each ship the same licence files.
            excludes += setOf("META-INF/LICENSE.md", "META-INF/LICENSE", "META-INF/NOTICE.md", "META-INF/NOTICE", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }
}

dependencies {
    // Hidden system API stubs: compile against them, never ship them.
    compileOnly(project(":systemstubs"))

    implementation(libs.kotlinx.coroutines.android)
    // Wireless debugging client: TLS with keying-material export, certificates, SPAKE2 pairing.
    implementation(libs.conscrypt.android)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.boringssl)
    implementation(libs.androidx.core.ktx)
    // Steps and sleep for the complication chips.
    implementation(libs.health.connect.client)
    // Wallet passes: drawing their barcodes, and showing them at the places the user chose.
    implementation(libs.zxing.core)
    implementation(libs.play.services.location)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
}
