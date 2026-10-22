plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "dev.ad.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ad.tv"
        minSdk = 31
        targetSdk = 36
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    // Release signing comes from CI secrets; without them the release APK is left unsigned.
    val keystore = System.getenv("KEYSTORE_FILE")?.let(::file)
    signingConfigs {
        if (keystore != null && keystore.exists()) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    sourceSets {
        getByName("main").assets.srcDir("../video")
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        resources.excludes += setOf(
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module",
            // BouncyCastle data files for algorithms we never use.
            "org/bouncycastle/pqc/**", "org/bouncycastle/x509/*.properties",
        )
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.8.0")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
