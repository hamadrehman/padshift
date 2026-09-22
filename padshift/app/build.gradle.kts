import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Release signing from padshift/keystore.properties (gitignored; CI writes it from repository secrets). Absent → unsigned release APK.
val keystore = Properties().apply { rootProject.file("keystore.properties").takeIf { it.exists() }?.reader()?.use { load(it) } }

android {
    namespace = "com.padshift.handheld"
    compileSdk = 34
    defaultConfig { applicationId = "com.padshift.handheld"; minSdk = 30; targetSdk = 22; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    signingConfigs {
        create("release") {
            if (keystore.isNotEmpty()) {
                storeFile = rootProject.file(keystore.getProperty("storeFile")); storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias"); keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    buildTypes { release { signingConfig = signingConfigs.getByName("release") } }
    lint { checkReleaseBuilds = false } // targetSdk 22 is intentional (rootless writes); lint would otherwise fail release builds
}
