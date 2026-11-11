import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val signingFile = rootProject.file(
    providers.environmentVariable("FAMILYCARE_SIGNING_PROPERTIES").orNull ?: "keystore.properties"
)
val signingValues = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}
val signingReady = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
    .all { !signingValues.getProperty(it).isNullOrBlank() }

val pleosSttFile = rootProject.file("pleos-stt.local.properties")
val pleosSttValues = Properties().apply {
    if (pleosSttFile.isFile) pleosSttFile.inputStream().use { load(it) }
}
fun buildConfigString(value: String?): String = "\"" + (value ?: "")
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r") + "\""

android {
    // Optional on Pleos 2.1: the platform exposes these classes under its own library name.
    useLibrary("android.car", false)
    namespace = "com.familycare.navigation"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.familycare.navigation"
        minSdk = 28
        targetSdk = 35
        versionCode = 39
        versionName = "0.21.0"
        buildConfigField("String", "PLEOS_STT_CLIENT_ID", buildConfigString(pleosSttValues.getProperty("client_id")?.trim()))
        buildConfigField("String", "PLEOS_STT_CLIENT_SECRET", buildConfigString(pleosSttValues.getProperty("client_secret")?.trim()))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").java.srcDir(rootProject.file("shared-ui"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file("shared-assets"))
    signingConfigs {
        if (signingReady) create("submission") {
            storeFile = signingFile.parentFile.resolve(signingValues.getProperty("storeFile"))
            storePassword = signingValues.getProperty("storePassword")
            keyAlias = signingValues.getProperty("keyAlias")
            keyPassword = signingValues.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            if (signingReady) signingConfig = signingConfigs.getByName("submission")
        }
    }
}

// A plain assembleRelease may be unsigned. This task never presents that file as a submission.
tasks.register("packageSubmission") {
    dependsOn("assembleRelease")
    doLast {
        check(signingReady) { "A team-owned release keystore and keystore.properties are required." }
        check(!signingValues.getProperty("keyAlias").equals("androiddebugkey", ignoreCase = true)) {
            "The Android debug key must not be used for submission."
        }
        fun filePart(name: String): String {
            val value = providers.gradleProperty(name).orNull ?: error("Missing -P$name")
            check(value.matches(Regex("[A-Za-z0-9가-힣_-]{1,60}"))) { "Invalid $name" }
            return value
        }
        val name = "${filePart("teamName")}_${filePart("appName")}_release.apk"
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        check(apk.isFile) { "Signed release APK was not produced." }
        val destination = rootProject.file("../apk/$name")
        check(!destination.exists()) { "APK already exists. Review and archive it before replacing." }
        destination.parentFile.mkdirs()
        apk.copyTo(destination)
        println("Prepared ${destination.name}; verify signature, installation and features before submitting.")
    }
}

dependencies {
    implementation(files(rootProject.file("local-sdk/tmap/tmap-sdk-3.7.aar")))
    implementation(files(rootProject.file("local-sdk/tmap/vsm-tmap-sdk-v2-eaa-2.0.14.aar")))
    implementation("com.google.flatbuffers:flatbuffers-java:24.3.25")
    implementation("com.google.android.material:material:1.6.1")
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.vehicle.sdk)
    implementation(libs.navi.sdk)
    implementation(libs.tts.sdk)
    implementation("ai.pleos.playground:SpeechToText:3.3.1")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation(libs.zxing.core)
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
