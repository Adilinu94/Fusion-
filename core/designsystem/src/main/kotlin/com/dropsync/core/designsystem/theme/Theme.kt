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
private val BrandBlack = Color(0xFF0D0D0D)
private val BrandWhite = Color(0xFFFFFFFF)
private val BrandLime = Color(0xFFDFFF2F)
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

// Dunkler Grund im Poweramp-Stil: kein reines Schwarz, sondern ein neutrales
// #1F1F1F als Basis. Erhoehte Flaechen (Karten, Sheets, Auswahlleiste,
// Mini-Player) liegen als hellere Stufen darueber; Vertiefungen etwas darunter.
private val DarkBase = Color(0xFF1F1F1F)
private val DarkSurfaceLow = Color(0xFF262626)
private val DarkSurface = Color(0xFF2A2A2A)
private val DarkSurfaceHigh = Color(0xFF323232)
private val DarkSurfaceVariant = Color(0xFF2E2E2E)
private val DarkOutline = Color(0xFF3C3C3C)
private val DarkTextGray = Color(0xFFB3B3B3)

private val LightColors =
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

private val DarkColors =
    darkColorScheme(
        primary = BrandLime,
        onPrimary = BrandBlack,
        secondary = BrandWhite,
        onSecondary = BrandBlack,
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
 * Material-3-Theme mit System-Dark-/Light-Mode (Bauplan 2.6, Schritt 12.1).
 * Die Markenidentitaet (Design.txt) verlangt eine feste Schwarz/Weiss/
 * Lime-Palette; Dynamic Color ist deshalb standardmaessig aus und kann
 * bewusst aktiviert werden. Die [accent]-Farbe ersetzt die primaere
 * Aktionsfarbe (Buttons, aktive Zustaende, Waveform, Now-Playing-Titel)
 * in Hell wie Dunkel; Default ist die Marken-Lime.
 */
@Composable
fun DropSyncTheme(
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
