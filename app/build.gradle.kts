import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Kept in local.properties, which is not committed: the Trakt and Nuvio keys, and the release signing key's settings.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.glasslauncher.tv"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.glasslauncher.tv"
        minSdk = 28
        targetSdk = 35
        versionCode = 36
        versionName = "2.2.0"
        // Trakt app (PKCE, no secret).
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${localProps.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "NUVIO_ANON_KEY", "\"${localProps.getProperty("NUVIO_ANON_KEY", "")}\"")
    }
    // Glassio Yahsun's release key (RELEASE_STORE_FILE and the rest in local.properties). Without it, a release build
    // is signed with the local debug key instead: fine to try, but it won't update a published release
    // (tools/release.sh refuses to publish it).
    val releaseStore = localProps.getProperty("RELEASE_STORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseStore != null) create("release") {
            storeFile = releaseStore
            storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // The backup's lock, checked on the computer: ./gradlew testReleaseUnitTest (tools/release.sh runs it).
    testImplementation("junit:junit:4.13.2")
}
