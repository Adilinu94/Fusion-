package com.dropsync.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.dropsync.core.designsystem.R

/**
 * Marken-Schriftfamilie gemaess Design.txt: Raleway (elegant, geometrisch,
 * sportlich). Das gesamte System nutzt bewusst nur eine Familie fuer maximale
 * Konsistenz.
 *
 * Raleway wird als gebuendelte OFL-TTF (statische Gewichte) unter
 * `core/designsystem/src/main/res/font/` ausgeliefert - offline, ohne
 * Google-Fonts-Provider. Genutzte Gewichte: 400/500/600/700/800.
 */
val BrandFontFamily: FontFamily =
    FontFamily(
        Font(R.font.raleway_regular, FontWeight.Normal),
        Font(R.font.raleway_medium, FontWeight.Medium),
        Font(R.font.raleway_semibold, FontWeight.SemiBold),
        Font(R.font.raleway_bold, FontWeight.Bold),
        Font(R.font.raleway_extrabold, FontWeight.ExtraBold),
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
        // Hero / grosse Zahlen (Design.txt Hero 72-96, H1 56)
        displayLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 57.sp,
                lineHeight = 58.sp,
                letterSpacing = (-1.0).sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        displayMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 45.sp,
                lineHeight = 46.sp,
                letterSpacing = (-0.8).sp,
                fontFeatureSettings = TABULAR_FIGURES,
            ),
        displaySmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                letterSpacing = (-0.4).sp,
            ),
        // Headlines (Design.txt H3 32, H4 24)
        headlineLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 36.sp,
                letterSpacing = (-0.2).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 30.sp,
            ),
        // Titles (Section-/Listenkoepfe)
        titleLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.1.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                letterSpacing = 0.1.sp,
            ),
        // Body (luftiger; Design.txt Body Large 18, Body 16, Caption 14)
        bodyLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 18.sp,
                lineHeight = 28.sp,
                letterSpacing = 0.15.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.15.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.2.sp,
            ),
        // Labels als Caps-taugliche Stile (Buttons, Toolbar, Small Label 12)
        labelLarge =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                letterSpacing = 0.5.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.8.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = BrandFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 1.0.sp,
            ),
    )
