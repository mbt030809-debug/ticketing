plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI 가 run_number 를 versionCode 로 넘김 (-PappVersionCode=N). 로컬 빌드는 1.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 1

// 릴리스 서명 키는 저장소에 두지 않고 환경변수(GitHub Secrets)로 주입한다.
val ksPath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val ksPassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }

android {
    namespace = "app.ticketing.couponmacro"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.ticketing.couponmacro"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = "0.1.$appVersionCode"
        // 앱 내 업데이트가 최신 릴리스를 조회할 저장소
        buildConfigField("String", "UPDATE_REPO", "\"mbt030809-debug/ticketing\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        if (ksPath != null && ksPassword != null && file(ksPath).exists()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPassword
                keyAlias = "couponmacro"
                keyPassword = ksPassword
                storeType = "pkcs12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 키가 없으면 디버그 키로 서명 (설치는 되지만 업데이트 배포용은 아님)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    // JVM 단위 테스트에서 org.json 실제 구현 사용 (android.jar 스텁 대체)
    testImplementation("org.json:json:20240303")
}
