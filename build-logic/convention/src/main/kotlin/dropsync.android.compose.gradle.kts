// dropsync.android.compose: Compose-Feature-Module (feature:*).
// Baut auf dropsync.android.library auf und aktiviert den Compose-Compiler
// (Kotlin 2.x: org.jetbrains.kotlin.plugin.compose) plus buildFeatures.compose.
plugins {
    id("dropsync.android.library")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    buildFeatures {
        compose = true
    }
}
