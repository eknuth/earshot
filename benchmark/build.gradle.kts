plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// This module is a developer tool, not part of the published Earshot library. It scores the
// raw transcripts captured by the on-device runners (Android ONNX, iOS WhisperKit) against the
// fixture references and writes the merged results.json the docs site renders. Keeping word
// error rate in exactly one place means both runtimes are judged by identical math.

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("dev.eknuth.earshot.bench.ScorerKt")
}

tasks.test {
    useJUnitPlatform()
}
