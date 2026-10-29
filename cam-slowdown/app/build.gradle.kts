plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jsbeth.camslow"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.jsbeth.camslow"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.8.0"
    }

    // 고정 디버그 키: 빌드마다 서명이 바뀌면 업데이트 설치가 막히므로 저장소에 둔다
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
