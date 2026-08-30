# R8-Regeln fuer den Release-Build (Bauplan Schritt 1.7).
# Room, Hilt und Compose liefern ihre Consumer-Regeln selbst mit.
# Regeln hier nur ergaenzen, wenn ein konkreter, dokumentierter Bedarf besteht.
#
# Stand 2026-08-30: `./gradlew :app:assembleRelease` laeuft gruen, R8 meldet
# keine fehlenden Klassen. Es ist bewusst keine Keep-Regel noetig, obwohl
# das Projekt an einer Stelle Reflection benutzt:
#
# OutputDeviceMonitor.bluetoothCodecName() ruft
# AudioManager.getBluetoothCodecStatus() per Reflection auf (versteckte API,
# nicht im android.jar). Reflection auf FRAMEWORK-Klassen ist von R8 nicht
# betroffen - android.* wird nie geschrumpft oder umbenannt, es ist nur
# Compile-Ziel. Eine Keep-Regel waere hier reine Dekoration.
#
# Kritisch wuerde es, wenn kuenftig per Reflection auf eigene oder
# Bibliotheksklassen zugegriffen wird (Class.forName auf com.dropsync.*,
# Gson/Moshi-Datenklassen, Service-Loader). Dann gehoert eine Keep-Regel
# hierher - mit Begruendung, welcher Aufruf sie braucht.
#
# Das Release-Gate laeuft seit 2026-08-30 in der CI mit
# (.github/workflows/ci.yml), damit eine solche Regression auffaellt.
