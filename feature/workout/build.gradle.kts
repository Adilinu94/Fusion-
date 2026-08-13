// :feature:workout — Trainings-Dashboard, Session, Routinen (Bauplan 3.2).
// Regel 3.2/4: nur Domain-Use-Cases, UI-State und :core:designsystem.
// AGP 9: Kotlin-Support ist im Android-Plugin eingebaut (kein kotlin.android).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.dropsync.feature.workout"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // android.util.Log (Shadow-Pfad in TrainViewModel) ist in
            // JVM-Unit-Tests ein Stub; ohne diese Option wirft jeder
            // Log.d-Aufruf "Method not mocked" und beendet den Collector.
            isReturnDefaultValues = true
        }
    }
}

tasks.withType<Test>().configureEach {
    // Gradle 9.5 on Windows passes -Djava.library.path unquoted when the PATH
    // contains spaces (e.g. "C:\Program Files\PowerShell\7"). The JVM launcher
    // then splits the argument and fails with "main class Files". Configure the
    // property AFTER AGP (configureEach runs last) with a space-free value.
    systemProperty("java.library.path", "C:\\dev\\jbr17\\bin")
    // Windows appends the system PATH to java.library.path anyway, which
    // reintroduces the spaces. Give the test worker a minimal, space-free
    // PATH so the unquoted argument survives the launcher.
    environment("PATH", "C:\\dev\\jbr17\\bin;C:\\Windows\\System32;C:\\Windows")
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:designsystem"))
    implementation(project(":domain:workout"))
    // Kalibrierungs-Wizard: Guided Calibration 2.0 (Stufen REST..REVIEW).
    implementation(project(":domain:sensor"))
    // Rest-Timer der Uebung nutzt die eine TimerEngine (Praezedenz
    // :feature:timer/:feature:player); DropSync-Rest via DropRestRequestBus.
    implementation(project(":domain:timer"))

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Interner NavHost des Trainings-Tabs (session/library/routines/...).
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(project(":core:testing"))
}
