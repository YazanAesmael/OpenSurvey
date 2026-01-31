// File: buildSrc/src/main/kotlin/pointx-android-hilt.gradle.kts
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

plugins {
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val libs: VersionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "implementation"(libs.findLibrary("hilt.android").get())
    "ksp"(libs.findLibrary("hilt.compiler").get())
}

// This provides specific arguments to the KSP processor for Hilt.
// Disabling 'shareTransitiveDependencies' is a known workaround for certain
// KSP bugs related to type resolution in recent Kotlin versions. It tells Hilt
// to use a slightly more conservative, but more stable, processing mode.
ksp {
    arg("hilt.shareTransitiveDependencies", "false")
}