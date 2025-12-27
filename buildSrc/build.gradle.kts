// File: PointX/buildSrc/build.gradle.kts

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("com.android.tools.build:gradle:8.9.3")

    implementation("com.google.dagger:hilt-android-gradle-plugin:2.56.2")
    implementation("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.1.21-2.0.1")

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.21")
    implementation("org.jetbrains.kotlin:kotlin-serialization:2.1.21")
    implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.21")
}

repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
}

kotlin {
    sourceSets.main {
        kotlin.srcDir("build/generated/ksp/main/kotlin")
    }
    sourceSets.test {
        kotlin.srcDir("build/generated/ksp/test/kotlin")
    }
}