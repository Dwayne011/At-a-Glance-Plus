// Compile-time stubs for the hidden Android Smartspace system API.
//
// These classes exist in the Android framework on every Pixel running Android 12 or newer but
// are not part of the public SDK. The stubs give the app module something to compile against;
// they are never packaged into the APK (the app depends on this module as compileOnly), so at
// runtime the real framework classes are used.
//
// Signatures follow the Android Open Source Project (Apache License 2.0). Bodies are stubs.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.ataglanceplus.systemstubs"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // The whole point of this module is to declare classes in the android.* namespace.
        abortOnError = false
    }
}
