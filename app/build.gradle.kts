plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cownow.weshit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cownow.weshit"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 只保留真机常见 ABI；模拟器调试时把 x86_64 加回来
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a"))
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Xposed API 只在编译期需要，运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")
    // 运行时按字符串检索目标方法，避开微信每版变化的混淆名
    implementation("org.luckypray:dexkit:2.3.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
