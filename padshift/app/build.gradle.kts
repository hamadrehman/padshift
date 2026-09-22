plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.padshift.handheld"
    compileSdk = 34
    defaultConfig { applicationId = "com.padshift.handheld"; minSdk = 30; targetSdk = 22; versionCode = 1; versionName = "0.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
