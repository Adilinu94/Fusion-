// :domain:settings — App-weite Einstellungen (Theme/Darstellung).
// Darf nur :core:common und :core:model kennen (Regel 3.2/2);
// kein Android-UI-, Room- oder ExoPlayer-Import.
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
