// :domain:library — Bibliotheks- und Markervertraege (ADR-0003).
// Gleiche Regeln wie alle Domain-Module (Bauplan 3.2/2):
// nur :core:common und :core:model; kein Android, Room oder ExoPlayer.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(libs.kotlinx.coroutines.core)
    // JsonElement-API fuer den Markerimport-Parser (6.1); kein Codegen noetig.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(project(":core:testing"))
}
