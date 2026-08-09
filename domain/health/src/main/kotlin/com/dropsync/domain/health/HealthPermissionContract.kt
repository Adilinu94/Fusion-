package com.dropsync.domain.health

import javax.inject.Qualifier

/**
 * Hilt-Qualifier fuer den Berechtigungs-Contract des Health-Connect-Dialogs
 * (Herzfrequenz-Plan 3.2, Review-Punkt 2).
 *
 * :data:health bindet darunter einen generischen
 * `ActivityResultContract<Set<String>, Set<String>>`; Features registrieren
 * ihn per `rememberLauncherForActivityResult`, ohne das Health-Connect-SDK
 * zu kennen (Modulregel 3.2/4).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HealthPermissionContract
