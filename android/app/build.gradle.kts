import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 签名密钥不在仓库里;`android/signing/keystore.properties` 由本机或 CI 恢复。
// 文件缺失时 release 依旧能构建,只是走默认 debug-sign,用于快速本地冒烟。
val keystorePropertiesFile = rootProject.file("signing/keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { load(it) }
    }
}

android {
    namespace = "com.simpssh"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.simpssh"
        minSdk = 26
        targetSdk = 34
        versionCode = 35
        versionName = "0.5.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8/minify 关掉:uniffi + JNA 依赖反射,开启会丢 symbol 触发运行时
            // NoSuchMethodError;要启用需要补一份保留规则。
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 输出名改成 `simpssh-<version>-<buildType>.apk`,分享出去一眼能看出版本。
    @Suppress("UnstableApiUsage")
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                .outputFileName = "simpssh-${versionName}-${name}.apk"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // JNA backs uniffi's Kotlin bindings.
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Syntax-highlighted code viewer.
    implementation("dev.snipme:highlights:1.0.0")

    // Image viewer: Coil decodes bytes/URIs; Telephoto adds pinch/pan/fling.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("me.saket.telephoto:zoomable-image-coil:0.13.0")

    // Streaming media player (video + audio) over SFTP via a custom DataSource.
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource:$media3")
}
