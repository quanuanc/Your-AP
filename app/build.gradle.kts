plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.cheng.yourap"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cheng.yourap"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    compileOnly(libs.xposed.api)
}