package com.dropsync.data.timer

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric-Testbasis mit [dagger.hilt.android.testing.HiltTestApplication],
 * damit `@AndroidEntryPoint`-Services in der JVM gebaut werden koennen
 * (Testinfrastruktur-Umbauplan T4: Hilt-Test-Setup fuer Schicht-2-Tests).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = dagger.hilt.android.testing.HiltTestApplication::class, sdk = [34])
abstract class HiltRobolectricTestCase
