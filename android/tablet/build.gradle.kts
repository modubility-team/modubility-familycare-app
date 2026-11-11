plugins { alias(libs.plugins.android.application) }
android {
    namespace = "com.familycare.tablet"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.familycare.tablet"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.21.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
}
