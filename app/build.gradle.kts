import org.gradle.kotlin.dsl.implementation

plugins {
    alias(libs.plugins.android.application)
}

// Android 应用模块。源码、资源 XML 和打包产物均按 UTF-8 维护。
android {
    namespace = "com.example.toolbox"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.toolbox"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        // Java 11 语言级别；源码字符集由根项目的 JavaCompile 配置固定为 UTF-8。
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}



dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.recyclerview)
    implementation(libs.material)
    implementation(libs.play.services.fitness)
    implementation (libs.pdfbox.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
