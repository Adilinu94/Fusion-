package com.dropsync.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dropsync.core.model.AccentColor

// Markenpalette gemaess Design.txt (Repo-Wurzel): Schwarz erzeugt Fokus,
// Lime erzeugt Energie, Weiss erzeugt Ruhe. Lime ist ausschliesslich fuer
// die primaere Aktion reserviert; Kontraste bleiben erhalten (Bauplan 2.6:
// Lime #DFFF2F zu dunklem Grund erfuellt AA deutlich).
// Intern sichtbar fuer Snapshot-Tests (ThemeColorSnapshotTest).
internal val BrandBlack = Color(0xFF101010)
internal val BrandWhite = Color(0xFFF7FBFF)
internal val BrandLime = Color(0xFFDFFF2F)

// Erweiterte Palette der Flowtimer-Integration (CONTEXT E4d): Violett traegt
// die semantische Rolle "Ziele" (secondary), der Grund hellt auf 141414 auf,
// genau ein heller Tile pro Screen laeuft in Helllila (secondaryContainer).
// Lime bleibt ausschliesslich Aktion; Violett ist KEINE waehlbare Akzentfarbe
// (AccentColor bleibt LIME | BLUE).
internal val BrandViolet = Color(0xFF756FFA)
internal val BrandGround = Color(0xFF141414)
internal val BrandLilac = Color(0xFFE7E6FB)

private val SoftGray = Color(0xFFF5F5F5)
private val BorderGray = Color(0xFFEAEAEA)
private val TextGray = Color(0xFF6B6B6B)

// Alternative Akzentfarbe (in den Einstellungen waehlbar): kraeftiges Blau.
// Auf Blau steht weisse Schrift (onPrimary), auf Lime schwarze — beide
// erfuellen den Kontrast in Hell wie Dunkel.
private val AccentBlue = Color(0xFF4564F9)

/** Primaer-/onPrimary-Paar der gewaehlten Akzentfarbe (gilt Hell wie Dunkel). */
private fun accentPair(accent: AccentColor): Pair<Color, Color> =
    when (accent) {
        AccentColor.LIME -> BrandLime to BrandBlack
        AccentColor.BLUE -> AccentBlue to BrandWhite
    }

// Dunkler Grund: 141414 (E4d) als Basis unter allen Tiles. Erhoehte Flaechen
// (Karten, Sheets, Auswahlleiste, Mini-Player) liegen als hellere Stufen
// darueber (1,09:1 bewusst subtil); Vertiefungen etwas darunter.
private val DarkBase = BrandGround
private val DarkSurfaceLow = Color(0xFF151515)
private val DarkSurface = Color(0xFF1D1D1D)
private val DarkSurfaceHigh = Color(0xFF252525)
private val DarkSurfaceVariant = Color(0xFF1D1D1D)
private val DarkOutline = Color(0xFF353535)
private val DarkTextGray = Color(0xFFB7B7B7)

internal val LightColors =
    lightColorScheme(
        primary = BrandLime,
        onPrimary = BrandBlack,
        secondary = BrandBlack,
        onSecondary = BrandWhite,
        tertiary = BrandBlack,
        onTertiary = BrandWhite,
        background = BrandWhite,
        onBackground = BrandBlack,
        surface = BrandWhite,
        onSurface = BrandBlack,
        surfaceVariant = SoftGray,
        onSurfaceVariant = TextGray,
        surfaceContainer = SoftGray,
        surfaceContainerLow = BrandWhite,
        surfaceContainerHigh = SoftGray,
        outline = BorderGray,
        outlineVariant = BorderGray,
    )

internal val DarkColors =
    darkColorScheme(
        primary = BrandLime,
        onPrimary = BrandBlack,
        secondary = BrandViolet,
        onSecondary = BrandGround,
        secondaryContainer = BrandLilac,
        onSecondaryContainer = BrandGround,
        tertiary = BrandLime,
        onTertiary = BrandBlack,
        background = DarkBase,
        onBackground = BrandWhite,
        surface = DarkBase,
        onSurface = BrandWhite,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkTextGray,
        surfaceContainer = DarkSurface,
        surfaceContainerLow = DarkSurfaceLow,
        surfaceContainerHigh = DarkSurfaceHigh,
        outline = DarkOutline,
        outlineVariant = DarkOutline,
        error = Color(0xFFFF6B6B),
        onError = BrandBlack,
    )

// Radien gemaess Design.txt: Cards 24, grosse Flaechen 32; Buttons sind
// in Material 3 bereits Pill-Shape.
private val BrandShapes =
    Shapes(
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(24.dp),
        large = RoundedCornerShape(32.dp),
    )

/**
 * FlowRep-Designsystem (Bauplan 2.6, Phase 1 A).
 *
 * Markenidentitaet: feste Schwarz/Weiss/Lime-Palette, Lime nur fuer primaere
 * Aktionen. Dark/Light-Mode aus System; Dynamic Color standardmaessig aus.
 * Die [accent]-Farbe ersetzt die primaere Aktionsfarbe (Buttons, aktive
 * Zustaende, Waveform, Now-Playing-Titel); Default ist die Marken-Lime.
 *
 * Plan Phase 6.1 (M3 Expressive; Evaluierung 2026-08-21, erneut bestaetigt
 * 2026-08-22): MaterialExpressiveTheme ist oeffentlich erst ab material3
 * 1.5.0 — laut Material-Blog mit dessen Stable-Release; 1.5.0 ist weiterhin
 * nur als alpha/beta verfuegbar, stabile Referenz bleibt 1.4.0 (BOM
 * 2026.06.01). Ein Alpha-Umstieg widerspricht der Projektregel (nur stabile
 * Versionen). Die Expressive-Ziele sind deshalb bewusst ohne die
 * Experimental-Api umgesetzt: Feder-Physik mit Bounce (Navigation-Pill,
 * Brand-Buttons, Play/Pause-Shape-Morph im Player, Swipe-Dismiss) und
 * sichtbarer State-Kontrast beim Play/Pause-Wechsel (Kreis <-> Squircle).
 * Wiedervorlage, wenn material3 1.5.0 stabil ist: FlowRepTheme auf
 * MaterialExpressiveTheme + MotionScheme.expressive() umstellen.
 */
@Composable
fun FlowRepTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: AccentColor = AccentColor.LIME,
    content: @Composable () -> Unit,
) {
    val (primary, onPrimary) = accentPair(accent)
    val base = if (darkTheme) DarkColors else LightColors
    MaterialTheme(
        colorScheme = base.copy(primary = primary, onPrimary = onPrimary),
        shapes = BrandShapes,
        typography = DropSyncTypography,
        content = content,
    )
}
