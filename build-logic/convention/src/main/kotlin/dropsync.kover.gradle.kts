// dropsync.kover: Coverage-Gate (D3/Entscheidung 5.4) als Convention-Plugin.
// Zieht die Kover-Konfiguration aus der Root-build.gradle.kts in build-logic:
// Filter (generierter Hilt-/Dagger-Code) und Floor-Ratsche. Die Floors
// stehen weiterhin zentral in der Root-build.gradle.kts (eine Map, ein Ort).
plugins {
    id("org.jetbrains.kotlinx.kover")
}

kover {
    reports {
        // Generierter Hilt-/Dagger-Code (Factories, Member-Injectors,
        // Hilt_-Wrapper, Aggregations-Paket) ist nicht von Hand testbar und
        // wuerde die Linien-Coverage mit 0 %-Klassen je Injektionspunkt
        // verwaessern; er zaehlt nicht in die Messung.
        filters {
            excludes {
                classes(
                    "*_Factory",
                    "*_MembersInjector",
                    "*_GeneratedInjector",
                    "*Hilt_*",
                    "*hilt_aggregated_deps*",
                )
            }
        }
    }
}
