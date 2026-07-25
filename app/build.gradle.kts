plugins {
    id("com.android.application")
}

kotlin {
    // AGP 9 内置 Kotlin：jvmTarget 与 compileOptions 对齐
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

android {
    namespace = "com.xvan.unireader.pad"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xvan.unireader.pad"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // zxing 的 appcompat 在 module metadata 里标记为可选，不随传递依赖打包；
    // 缺 androidx.core 会在 CaptureManager.openCameraWithPermission 崩 NoClassDefFoundError(ContextCompat)
    implementation("androidx.appcompat:appcompat:1.7.1")
    testImplementation("junit:junit:4.13.2")
}
