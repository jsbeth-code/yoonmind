plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jsbeth.camslow"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jsbeth.camslow"
        minSdk = 26
        targetSdk = 34
        versionCode = 18
        versionName = "0.9.2"
        // 카카오 네이티브 앱 키: CI는 GitHub Secrets KAKAO_NATIVE_APP_KEY 환경변수, 로컬은 -PKAKAO_NATIVE_APP_KEY=…
        val kakaoKey = (System.getenv("KAKAO_NATIVE_APP_KEY") ?: (project.findProperty("KAKAO_NATIVE_APP_KEY") as String?) ?: "").trim()
        buildConfigField("String", "KAKAO_NATIVE_APP_KEY", "\"$kakaoKey\"")
    }

    buildFeatures {
        buildConfig = true
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
    // 카카오내비 SDK: 목적지 없는 "안전 운행" 단속카메라 정보
    implementation("com.kakaomobility.knsdk:knsdk_ui:1.12.7")
    // KNSDK가 앱 쪽에 있다고 가정하는 라이브러리(없으면 SDK 리소스·클래스가 깨진다)
    implementation("com.kakao.maps.open:android:2.15.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
