import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

fun signingValue(key: String): String? {
    val envValue = System.getenv(key)
    val gradleValue = providers.gradleProperty(key).orNull
    val fileValue = keystoreProperties.getProperty(key)
    return sequenceOf(envValue, gradleValue, fileValue)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
}

val signingStoreFile = signingValue("FLUE_SIGNING_STORE_FILE")
val signingStorePassword = signingValue("FLUE_SIGNING_STORE_PASSWORD")
val signingKeyAlias = signingValue("FLUE_SIGNING_KEY_ALIAS")
val signingKeyPassword = signingValue("FLUE_SIGNING_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    signingStoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }
val disableReleaseMinify = providers.gradleProperty("flue.disableReleaseMinify")
    .map { it.toBoolean() }
    .orElse(false)

android {
    namespace = "com.flue.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dw.launcher"
        minSdk = 27
        targetSdk = 36
        versionCode = 27
        versionName = "2.3.2"
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = rootProject.file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            val minifyRelease = !disableReleaseMinify.get()
            isMinifyEnabled = minifyRelease
            isShrinkResources = minifyRelease
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // libproot_exec.so is launched by ProcessBuilder, so Android must
            // materialise it as a real executable in nativeLibraryDir.
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.mediarouter)
    implementation(libs.recyclerview)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    // material3 单独提到 1.5 beta：BOM 2026.04 捆绑的 1.4.0 里
    // MaterialExpressiveTheme / MotionScheme.expressive 仍是 internal，
    // 真 M3 Expressive 主题需要 1.5 的公开 API。
    implementation("androidx.compose.material3:material3:1.5.0-alpha14")
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.cloudy)
    implementation(libs.graphics.shapes)
    // miuix-icons 0.8.8 publishes its MiuixIcons receiver through a runtime
    // dependency. Add the matching core module explicitly so Kotlin can
    // resolve the official extension properties at compile time.
    implementation(libs.miuix.core)
    implementation(libs.miuix.icons)
    implementation(libs.ffmpeg.kit.video)
    // ffmpeg-kit-maintained 8.1.7 publishes an empty dependency POM even though
    // its Java API still calls com.arthenica.smartexception.java.Exceptions.
    implementation(libs.smart.exception.java)
    // WatchMaker face scripts run inside a Flue-owned whitelist sandbox.  LuaJ is
    // used only as the pure-Java VM/compiler; its file, package and Java bridge
    // libraries are never exposed to a face.
    implementation(libs.luaj.jse)
    implementation(libs.xz)

    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
