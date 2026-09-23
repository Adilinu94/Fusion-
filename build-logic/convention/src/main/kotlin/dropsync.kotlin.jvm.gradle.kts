// dropsync.kotlin.jvm: JVM-Module (domain:*, core:common/model, training-core).
// Zentralisiert: Java 17 Toolchain + jvmTarget (vorher in jedem Modul
// wiederholt, Bericht Paket 4.19).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
