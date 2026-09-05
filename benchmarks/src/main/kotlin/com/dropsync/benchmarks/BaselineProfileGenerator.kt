package com.dropsync.benchmarks

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Erzeugt das Baseline Profile (Befund B-UI-5).
 *
 * Bis hierher war die Infrastruktur unvollstaendig: das
 * `androidx.baselineprofile`-Plugin lag auf `:app` und `:benchmarks`, der Task
 * `:app:generateBaselineProfile` existierte und lief gruen durch — aber es gab
 * weder die Producer-Verdrahtung (`baselineProfile(project(":benchmarks"))`
 * in `:app`) noch diese Klasse. `merge` und `copy` arbeiteten auf einer leeren
 * Eingabe, `app/src/release/generated/baselineProfiles/` blieb leer. Ein Task,
 * der nichts produziert und trotzdem gruen ist, ist schlimmer als ein
 * fehlender: er sieht nach Abdeckung aus.
 *
 * Lauf: `./gradlew :app:generateBaselineProfile` mit verbundenem Geraet oder
 * Emulator. Voraussetzung ist Root oder API 33+ (das Sammeln liest
 * `/data/misc/profiles`); auf einem Emulator muss das Systemabbild `aosp`
 * sein, nicht `google_apis`. Das Ergebnis landet in
 * `app/src/release/generated/baselineProfiles/baseline-prof.txt` und wird von
 * dort per `profileinstaller` mit ausgeliefert.
 *
 * `includeInStartupProfile = true` markiert den Lauf zusaetzlich als
 * Startup-Profil (DEX-Layout-Optimierung). Das ist hier richtig, weil der
 * Block genau den Kaltstart abdeckt.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() =
        baselineProfileRule.collect(
            // Ohne Suffix: gesammelt wird gegen den non-debuggable Build
            // (`nonMinifiedRelease`), den das Plugin aus `release` ableitet.
            // `release` hat keinen applicationIdSuffix — nur `debug` hat
            // `.debug`. Genau hier lag der zweite Teil von B-UI-5.
            packageName = TARGET_PACKAGE,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()
            // Die Startroute ist MUSIC (LibraryScreen). waitForIdle deckt die
            // erste Composition samt Berechtigungspruefung ab; ohne sie endet
            // der Block teils vor dem ersten Frame.
            device.waitForIdle()
        }

    private companion object {
        /**
         * Deckungsgleich mit `applicationId` in `app/build.gradle.kts`.
         * Bewusst als Konstante und nicht per `InstrumentationRegistry`:
         * das Plugin setzt `androidx.benchmark.targetPackageName` selbst,
         * aber ein hier abweichender Wert wuerde stumm gegen ein anderes
         * Paket sammeln.
         */
        const val TARGET_PACKAGE = "com.dropsync.app"
    }
}
