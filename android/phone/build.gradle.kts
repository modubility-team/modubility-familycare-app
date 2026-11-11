plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "com.familycare.phone"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.familycare.phone"
        minSdk = 28
        targetSdk = 35
        versionCode = 29
        versionName = "0.21.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["allowLocalHttp"] = "false"
    }
    flavorDimensions += "product"
    productFlavors {
        create("navigation") {
            dimension = "product"
            applicationIdSuffix = ".navigation"
            resValue("string", "app_name", "WithME 내 차")
            buildConfigField("String", "PRODUCT", "\"navigation\"")
            manifestPlaceholders["joinScheme"] = "familycare-navigation"
        }
        create("care") {
            dimension = "product"
            applicationIdSuffix = ".care"
            resValue("string", "app_name", "WithME 가족")
            buildConfigField("String", "PRODUCT", "\"care\"")
            manifestPlaceholders["joinScheme"] = "familycare-care"
        }
    }
    buildTypes {
        debug { manifestPlaceholders["allowLocalHttp"] = "true" }
        release { isDebuggable = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared-ui"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file("shared-assets"))
}
dependencies {
    implementation(files(rootProject.file("local-sdk/tmap/tmap-sdk-3.7.aar")))
    implementation(files(rootProject.file("local-sdk/tmap/vsm-tmap-sdk-v2-eaa-2.0.14.aar")))
    implementation("com.google.flatbuffers:flatbuffers-java:24.3.25")
    implementation("com.google.android.material:material:1.6.1")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
