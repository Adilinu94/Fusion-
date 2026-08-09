// :domain:audio — DSP-Mathematik und Audio-Vertraege (ADR-0005).
// Reine JVM: kein Android, kein Media3; alle Filter-/Konvertierungs-
// berechnungen sind hier deterministisch testbar.
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

    testImplementation(project(":core:testing"))
}
