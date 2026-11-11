plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// A stable export location regardless of the checkout path or Windows build relocation.
tasks.register<Copy>("exportDebugApks") {
    dependsOn(":app:assembleDebug", ":phone:assembleNavigationDebug", ":phone:assembleCareDebug")
    into(rootProject.layout.projectDirectory.dir("../dist/apk"))
    from(project(":app").layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")) {
        rename { "01-FamilyCare-vehicle-debug.apk" }
    }
    from(project(":phone").layout.buildDirectory.file("outputs/apk/navigation/debug/phone-navigation-debug.apk")) {
        rename { "02-FamilyCare-navigation-debug.apk" }
    }
    from(project(":phone").layout.buildDirectory.file("outputs/apk/care/debug/phone-care-debug.apk")) {
        rename { "03-FamilyCare-care-debug.apk" }
    }
    doLast {
        listOf("01-FamilyCare-vehicle-debug.apk", "02-FamilyCare-navigation-debug.apk", "03-FamilyCare-care-debug.apk").forEach {
            check(destinationDir.resolve(it).isFile) { "Missing exported APK: $it" }
        }
        println("Development APKs: $destinationDir")
    }
}

// Gradle 8.10/JDK 17 test workers on Windows can misread non-ASCII classpaths.
tasks.register<Copy>("exportDemoApks") {
    dependsOn(":app:assembleDebug", ":app:assembleDebugAndroidTest")
    into(rootProject.layout.projectDirectory.dir("../dist/demo"))
    from(project(":app").layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    from(project(":app").layout.buildDirectory.file("outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
}

// Keep the user's sources in place; only generated outputs use an ASCII path.
if (System.getProperty("os.name").startsWith("Windows") &&
    rootDir.absolutePath.any { it.code > 127 }) {
    val projectKey = Integer.toHexString(rootDir.absolutePath.hashCode())
    subprojects {
        layout.buildDirectory.set(
            File(gradle.gradleUserHomeDir, "family-care-builds/$projectKey/$name")
        )
    }
}

tasks.register<Copy>("exportTabletApk") {
    dependsOn(":tablet:assembleDebug")
    into(rootProject.layout.projectDirectory.dir("../dist/apk"))
    from(project(":tablet").layout.buildDirectory.file("outputs/apk/debug/tablet-debug.apk")) {
        rename { "04-FamilyCare-tablet-debug.apk" }
    }
}
