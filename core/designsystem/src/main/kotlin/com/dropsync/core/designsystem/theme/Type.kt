package com.dropsync.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dropsync.core.designsystem.R

/**
 * Marken-Schriftfamilie der FlowRep-Mobiloberflaeche: Poppins. Das gesamte
 * System nutzt bewusst nur eine Familie fuer maximale Konsistenz.
 *
 * Poppins wird als gebuendelte OFL-TTF (statische Gewichte) unter
 * `core/designsystem/src/main/res/font/` ausgeliefert - offline, ohne
 * Google-Fonts-Provider. Genutzte Gewichte: 400/500/700.
 */
val BrandFontFamily: FontFamily =
    FontFamily(
        Font(R.font.poppins_regular, FontWeight.Normal),
        Font(R.font.poppins_medium, FontWeight.Medium),
        Font(R.font.poppins_bold, FontWeight.SemiBold),
        Font(R.font.poppins_bold, FontWeight.Bold),
    )

// Aktivierung tabellarischer Ziffern fuer grosse Zahlen (Timer, Statistiken),
// damit Ziffern nicht springen. Bleibt wirkungslos, falls die Familie das
// Feature nicht kennt.
private const val TABULAR_FIGURES = "tnum"

/**
 * Typo-Skala gemaess Design.txt, auf mobile sp gemappt:
 * Hero/Display eng (negatives Tracking, knappe Zeilenhoehe, starke Gewichtung),
 * Body luftiger, Labels als Caps mit leichtem Tracking. Gewichte v. a. 600/700/800.
 */
val DropSyncTypography: Typography =
    Typography(
        // Display und grosse Zahlen.
        displayLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 48.sp,
                lineHeight = 52.sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        displayMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 42.sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        displaySmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        // Headlines.
        headlineLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        // Titles (Section-/Listenkoepfe)
        titleLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        // Body und Metadaten. C1: jede Stufe ist eindeutig (vorher waren
        // bodyLarge/bodyMedium sowie alle drei Labels identisch) — Proportionen
        // nach M3, Groessen und Poppins aus dem Markensystem.
        bodyLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.25.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.2.sp,
            ),
        // Labels fuer Buttons, Navigation und Status (C1-differenziert).
        labelLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.1.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )
