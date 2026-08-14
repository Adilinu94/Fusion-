package com.dropsync.app

import android.app.Application

/**
 * Test-Application fuer instrumentierte Tests (Testinfra-Plan Schritt 4).
 *
 * Bewusst OHNE `@HiltAndroidApp`: Hilt erlaubt nur eine App-Root pro
 * Modul; die Produktions-`DropSyncApplication` ist die Root. Die Tests
 * verwenden `@HiltAndroidTest` + ggf. `@UninstallModules`, Hilt baut
 * den Test-Graph ueber die Produktions-Root. Diese Application wird nur
 * als `customTestApplicationClass` registriert, damit der Runner eine
 * leere, stabile Application hat.
 */
class HiltTestApplication : Application()
