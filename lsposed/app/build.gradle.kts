plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.leqwiss.dockcontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.leqwiss.dockcontrol"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release signing. The keystore is committed on purpose (it is a public hobby module,
    // the password is public too). It only guarantees a *stable* signature between builds,
    // so an update can be installed over the previous version.
    val keystoreFile = rootProject.file("keystore/dockcontrol.jks")

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = keystoreFile
                storePassword = "dockcontrol"
                keyAlias = "dockcontrol"
                keyPassword = "dockcontrol"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Keep META-INF/xposed/** intact - it is the module manifest.
            excludes += setOf(
                "META-INF/*.kotlin_module",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    lint {
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Provided by the framework at runtime - must never be bundled into the APK.
    compileOnly("io.github.libxposed:api:101.0.1")
    compileOnly("androidx.annotation:annotation:1.9.1")
}
