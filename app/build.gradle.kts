plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.0" // ✅ 추가
    kotlin("kapt")//추상코드를 컴파일러에게 번역해주는 중계기
}

android {
    namespace = "com.example.anki_advanced"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.anki_advanced"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
        compose = true
    }



}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/legacy/**")
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)


     implementation(platform("androidx.compose:compose-bom:2024.09.00"))
     implementation("androidx.compose.ui:ui")
     implementation("androidx.compose.material3:material3")
     implementation("androidx.compose.material:material-icons-extended")
     implementation("androidx.activity:activity-compose:1.9.0")
     debugImplementation("androidx.compose.ui:ui-tooling")      // Preview 렌더링용

    // ViewModel + Compose 연동
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Compose Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")



    // Room
    implementation("androidx.room:room-runtime:2.6.1")//Room의 핵심 실행 라이브러리
    implementation("androidx.room:room-ktx:2.6.1")//Kotlin 전용 확장 api
    add("kapt", "androidx.room:room-compiler:2.6.1")//추상코드 활성화하는 컴파일러

    // Gemini API 호출(OkHttp) + API 키 암호화 저장(EncryptedSharedPreferences)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}