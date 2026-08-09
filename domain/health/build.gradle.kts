// :domain:health — Herzfrequenz ueber Health Connect (Herzfrequenz-Plan 3.1).
// Reines JVM-Modul (Regel 3.2/2): kein Android-, Room- oder Media3-Import;
// der Health-Connect-SDK-Typ bleibt in :data:health.
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
    implementation(libs.kotlinx.coroutines.core)
    // Nur fuer den @Qualifier des Berechtigungs-Contracts (3.2 im Plan).
    implementation(libs.javax.inject)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit4)
}
