import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.maven.publish)
}

// Version is 0.1.0 by default; CI overrides it from the git tag with -Pearshot.version=<tag without v>.
val earshotVersion = providers.gradleProperty("earshot.version").getOrElse("0.1.0")

kotlin {
    // The transcription seam uses expect/actual classes (still flagged Beta by the compiler).
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        // Publish the release variant as the Android artifact of the multiplatform library.
        publishLibraryVariants("release")
        compilations.all {
            kotlinOptions {
                jvmTarget = "17"
            }
        }
    }

    // Bundle the three iOS slices into one XCFramework so Swift Package Manager can consume a
    // single binary target. Produces `assembleEarshotReleaseXCFramework` ->
    // build/XCFrameworks/release/Earshot.xcframework.
    val xcf = XCFramework("Earshot")
    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach {
        it.binaries.framework {
            baseName = "Earshot"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            // Whisper runs through ONNX Runtime on Android; extensions supply the BPE decoder op.
            implementation(libs.onnxruntime.android)
            implementation(libs.onnxruntime.extensions.android)
        }
        // iosMain needs no extra deps: audio extraction uses AVFoundation and the ASR
        // runtime (WhisperKit / CoreML) is injected from the Swift side at runtime.
    }
}

android {
    namespace = "dev.eknuth.earshot"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

mavenPublishing {
    // New namespaces publish through the Central Portal (OSSRH is retired). Manual release so a
    // human approves the irreversible step in the Portal UI after the staged check passes.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    coordinates("dev.eknuth", "earshot", earshotVersion)

    pom {
        name.set("Earshot")
        description.set(
            "On-device speech-to-text for iOS and Android, from one Kotlin Multiplatform core."
        )
        inceptionYear.set("2026")
        url.set("https://github.com/eknuth/earshot")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("eknuth")
                name.set("Edwin Knuth")
                url.set("https://github.com/eknuth")
            }
        }

        scm {
            url.set("https://github.com/eknuth/earshot")
            connection.set("scm:git:git://github.com/eknuth/earshot.git")
            developerConnection.set("scm:git:ssh://git@github.com/eknuth/earshot.git")
        }
    }
}
