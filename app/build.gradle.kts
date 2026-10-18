import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun localOrEnv(localKey: String, envKey: String): String? =
    localProperties.getProperty(localKey) ?: System.getenv(envKey)

val dshVersion = providers.gradleProperty("dshVersion").orNull ?: "0.2.1-rc.2"
val appVersionName = providers.gradleProperty("appVersionName").orNull ?: "0.1.0"
val runtimeVersion = providers.gradleProperty("runtimeVersion").orNull ?: "dev"
val githubRepo = providers.gradleProperty("githubRepo").orNull ?: "Luqijian/DeepSeek_Harness_Android"
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1

val releaseStoreFile = localOrEnv("release.storeFile", "KEYSTORE_PATH")
val releaseStorePassword = localOrEnv("release.storePassword", "KEYSTORE_PASSWORD")
val releaseKeyAlias = localOrEnv("release.keyAlias", "KEY_ALIAS")
val releaseKeyPassword = localOrEnv("release.keyPassword", "KEY_PASSWORD")
val hasReleaseKey = !releaseStoreFile.isNullOrBlank() && file(releaseStoreFile).exists()

android {
    namespace = "io.github.luqijian.harnessandroid"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.luqijian.harnessandroid"
        minSdk = 26
        targetSdk = 36
        versionCode = ciVersionCode
        versionName = "$appVersionName+dsh$dshVersion"
        ndk {
            abiFilters += "arm64-v8a"
        }
        buildConfigField("String", "DSH_VERSION", "\"$dshVersion\"")
        buildConfigField("String", "RUNTIME_VERSION", "\"$runtimeVersion\"")
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        buildConfigField("String", "APP_VERSION_NAME", "\"$appVersionName\"")
        buildConfigField("boolean", "DIRECT_STORAGE", "true")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.apache.commons:commons-compress:1.27.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
