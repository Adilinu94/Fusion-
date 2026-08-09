// :core:testing — Fakes, Testdaten, Regeln (Bauplan 3.2).
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
    api(project(":core:common"))
    api(libs.kotlinx.coroutines.test)
    api(libs.junit4)
    api(libs.turbine)

    testImplementation(libs.kotlinx.coroutines.core)
}
