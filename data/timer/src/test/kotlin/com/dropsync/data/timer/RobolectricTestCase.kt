package com.dropsync.data.timer

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Basisklasse fuer Robolectric-Tests (Testpyramide Schicht 2):
 * Android-Framework wird in der JVM simuliert — kein Emulator noetig.
 * `sdk = 34` als Default; einzelne Tests koennen per `@Config` abweichen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
abstract class RobolectricTestCase
