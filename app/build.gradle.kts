plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.mcpanel"; compileSdk = 35
    // applicationId MUST be io.mcpanel: the bundled Termux bootstrap embeds
    // paths of the exact same length (10 chars) as com.termux; Embed.kt
    // byte-patches them at extraction time. Changing the length breaks ELFs.
    defaultConfig { applicationId = "io.mcpanel"; minSdk = 26; targetSdk = 28; versionCode = 8; versionName = "0.7" }

    // One APK per architecture instead of a universal one: each flavor
    // bundles only its own Termux bootstrap (armv7 -> 32-bit arm, armv8 ->
    // 64-bit aarch64) and a native stub that locks the installer/process to
    // that ABI, so the extracted environment always matches the device.
    flavorDimensions += "abi"
    productFlavors {
        create("armv7") {
            dimension = "abi"
            versionNameSuffix = "-armv7"
            ndk { abiFilters += "armeabi-v7a" }
        }
        create("armv8") {
            dimension = "abi"
            versionNameSuffix = "-armv8"
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    ndkVersion = "26.1.10909125"
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    lint { checkReleaseBuilds = false; abortOnError = false }
    buildTypes { release {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        // Signed with the debug key so the APK is installable via sideload.
        // Replace with a real release keystore for public distribution.
        signingConfig = signingConfigs.getByName("debug")
    } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
