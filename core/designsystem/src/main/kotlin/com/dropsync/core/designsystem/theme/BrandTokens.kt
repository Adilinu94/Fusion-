package com.dropsync.core.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * C1-Token: feste, gut unterscheidbare Kategorie-Farben der Bibliothek
 * (Poweramp-artig, Dark-first). Die Palette liegt bewusst im Theme, damit
 * Features keine eigenen `Color(0x...)`-Literale tragen (Grep-Gate
 * `tools/design_check.py`). Semantische Zuordnung Kategorie -> Token bleibt
 * im Feature (dort lebt die Kategorie-Aufzaehlung).
 */
object CategoryTints {
    val lime = BrandLime
    val orange = Color(0xFFFFB74D)
    val amber = Color(0xFFFFD54F)
    val blue = Color(0xFF64B5F6)
    val purple = Color(0xFFBA68C8)
    val pink = Color(0xFFF06292)
    val green = Color(0xFF81C784)
    val cyan = Color(0xFF4DD0E1)
    val red = Color(0xFFFF6B6B)
    val teal = Color(0xFF4DB6AC)
    val deepOrange = Color(0xFFFF8A65)
    val indigo = Color(0xFF7986CB)
}

/**
 * C1-Token: neutrale Overlays auf Cover-Bildern. Anders als die Markenpalette
 * sind sie absichtlich absolut (Schwarz-Weiss), weil sie auf beliebigen
 * Bildinhalten lesbar bleiben muessen — ein themenabhaengiger Ton wuerde auf
 * hellen Covern verschwinden.
 */
object OverlayTokens {
    /** Abdunkelnder Verlauf/Ton unter Text auf Bildern. */
    val scrim: Color = Color.Black

    /** Text und Icons auf dem [scrim]. */
    val onScrim: Color = Color.White
}
