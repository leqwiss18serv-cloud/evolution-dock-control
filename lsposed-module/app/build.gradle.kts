plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "cloud.leqwiss.dockcontrol"
    compileSdk = 36
    defaultConfig {
        applicationId = "cloud.leqwiss.dockcontrol"
        minSdk = 29
        targetSdk = 36
        versionCode = 7
        versionName = "3.0.0"
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation(files("libs/libxposed-interface-102.0.0.aar"))
    implementation(files("libs/libxposed-service-102.0.0.aar"))
}
