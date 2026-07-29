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
    // 两种模式同一个 App（ANDROID-STANDALONE-PLAN §1）：包名不再带 .pad
    namespace = "com.xvan.unireader"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xvan.unireader"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"

        // 数据层的测试只能跑在设备上：android.database.sqlite 在 JVM 单测里是空壳
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Pdfium 是 native 库，多 ABI 会把 APK 撑几十 MB。自用侧载只要 arm64（平板与
        // Apple Silicon 上的模拟器都是 arm64）；**x86_64 模拟器因此装不上**，是刻意取舍。
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // zxing 的 appcompat 在 module metadata 里标记为可选，不随传递依赖打包；
    // 缺 androidx.core 会在 CaptureManager.openCameraWithPermission 崩 NoClassDefFoundError(ContextCompat)
    implementation("androidx.appcompat:appcompat:1.7.1")
    // 模式1 独立版的 PDF 渲染（M2 起用）。选它而非内置 PdfRenderer：后者只能出图、没有文字层
    // 和书签，下一版的搜索/选择/目录会全部卡死；MuPDF 功能全但 AGPL。见 ANDROID-STANDALONE-PLAN §4。
    //
    // **版本被工具链卡住，别随手升**：AGP 9.2.0 内置的 Kotlin 编译器是 2.2.0，最多读元数据 2.3.0。
    // pdfiumandroid 2.0.2/2.0.3 是 Kotlin 2.4.10 编的（元数据 2.4.0）→ 整个 compileDebugKotlin
    // 直接失败（连 kotlin.Unit 都报 incompatible）。2.0.1 是 Kotlin 2.3.21，正好在可读范围内。
    // 要用 2.0.3 就得先把 Kotlin 编译器提到 2.4（换掉 AGP 内置 Kotlin），那是独立一件事。
    implementation("io.legere:pdfiumandroid:2.0.1")
    testImplementation("junit:junit:4.13.2")
    // 插桩测试（:app:connectedDebugAndroidTest）：数据层要在真 SQLite 上验，JVM 单测跑不了。
    // 这两条只影响 androidTest 变体，不进 APK、也不影响 :app:assembleDebug 的离线构建。
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
