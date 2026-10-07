import java.util.Properties

plugins {
    id("com.android.application")
}

// 배포용 서명 키: 저장소 밖(keystore.properties, keystore/)에 둔다. 없는 PC에서는 디버그 키로 서명
val releaseKeys = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "com.dsviewer.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.dsviewer.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 10
        versionName = "1.0.4"
        // 필기 글자 인식(ML Kit)의 네이티브 라이브러리가 CPU 구조마다 들어 APK가 커지므로 요즘 태블릿·폰의 arm64만 넣는다
        // (전자칠판처럼 32비트·x86 기기용 범용 APK는 -PallAbis 를 붙여 만든다)
        if (!project.hasProperty("allAbis")) ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = rootProject.file("keystore/dsnote-release.jks")
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }

    buildTypes {
        debug {
            // 시험용 빌드는 배포본(com.dsviewer.app)과 다른 앱 'DSnote 개발'로 깔린다: 지우고 다시 깔아도 진짜 앱의 자료·버전이 안 건드려진다
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = false
            // 다른 기기에 옮겨 설치하는 APK: 배포용 키로 서명 (디버그 키로 서명한 APK는 플레이 프로텍트가 더 의심한다)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    // PDF 주석 저장/읽기
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // 필기 글자 인식 (기기 안 처리, 한국어 모델은 처음 한 번 내려받는다) → 필기 검색
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
