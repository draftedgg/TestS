plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android { namespace = "com.mcpanel"; compileSdk = 35
    // applicationId MUST be io.mcpanel: the bundled Termux bootstrap embeds
    // paths of the exact same length (10 chars) as com.termux; Embed.kt
    // byte-patches them at extraction time. Changing the length breaks ELFs.
    defaultConfig { applicationId = "io.mcpanel"; minSdk = 26; targetSdk = 28; versionCode = 3; versionName = "0.3.0" }
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
