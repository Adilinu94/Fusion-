package com.dropsync.core.designsystem.chart

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// Waveform im Poweramp-Stil (Marker/Waveform-Plan Phase 3): eigene
// Compose-Canvas-Zeichnung wie LineChart/BarChart, keine neue
// Abhaengigkeit. Lime-Akzent fuer den gespielten Anteil, neutrales Grau
// fuer den Rest; die Wellenform ersetzt die klassische Zeitleiste als
// Bedienflaeche (Tap = Sprung, Drag = Vorschau, Sprung beim Loslassen).

/**
 * Reine Koordinaten-Mathematik der Waveform, getrennt von Compose und
 * damit deterministisch testbar (WaveformBucketMappingTest).
 */
object WaveformMapping {
    /** Ein gezeichneter Balken in Canvas-Koordinaten. */
    data class Bar(
        val left: Float,
        val top: Float,
        val width: Float,
        val height: Float,
    )

    /**
     * Bildet normalisierte Min/Max-Buckets ([-1..1]) auf vertikale Balken
     * ab. Ein stiller Bucket behaelt eine Mindesthoehe von einem Pixel um
     * die Mittellinie, damit die Spur nie optisch abreisst.
     */
    fun mapToBars(
        buckets: List<Pair<Float, Float>>,
        width: Float,
        height: Float,
        gapFraction: Float = 0.25f,
    ): List<Bar> {
        if (buckets.isEmpty() || width <= 0f || height <= 0f) return emptyList()
        val slot = width / buckets.size
        val barWidth = (slot * (1f - gapFraction.coerceIn(0f, 0.9f))).coerceAtLeast(1f)
        val center = height / 2f
        return buckets.mapIndexed { index, (min, max) ->
            val top = center - max.coerceIn(-1f, 1f) * center
            val bottom = center - min.coerceIn(-1f, 1f) * center
            Bar(
                left = index * slot,
                top = top,
                width = barWidth,
                height = (bottom - top).coerceAtLeast(1f),
            )
        }
    }

    /**
     * X-Position in einen Fortschrittsanteil [0..1] uebersetzen.
     */
    fun fractionAt(
        x: Float,
        width: Float,
    ): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /**
     * Index des Markers nahe [fraction] innerhalb [slop], sonst -1.
     * Grundlage fuer "Long-Press nahe Tick loescht statt zu setzen"
     * (Phase 4/8) und den Marker-Drag-Start.
     */
    fun nearestMarkerIndex(
        fractions: List<Float>,
        fraction: Float,
        slop: Float = 0.03f,
    ): Int {
        if (fractions.isEmpty()) return -1
        val index =
            fractions.indices.minByOrNull { abs(fractions[it] - fraction) } ?: return -1
        return if (abs(fractions[index] - fraction) <= slop) index else -1
    }

    /**
     * Balken als flaches Float-Array (left, top, width, height je Bar)
     * statt `List<Bar>`-Objekten. Der Canvas-Zeichenpfad nutzt diese
     * Form, damit pro Frame keine Objekt-Allokation anfaellt
     * (Wissensdoku Abschnitt 25: "Canvas nur aus vorbereiteten Arrays
     * zeichnen", "bei Scrubbing nicht pro Pixel neue Objekte
     * allokieren"). Gleiche Mathematik wie [mapToBars], deterministisch
     * testbar.
     */
    fun toFlatBars(
        buckets: List<Pair<Float, Float>>,
        width: Float,
        height: Float,
        gapFraction: Float = 0.25f,
    ): FloatArray {
        if (buckets.isEmpty() || width <= 0f || height <= 0f) return EMPTY_FLOATS
        val slot = width / buckets.size
        val barWidth = (slot * (1f - gapFraction.coerceIn(0f, 0.9f))).coerceAtLeast(1f)
        val center = height / 2f
        val out = FloatArray(buckets.size * 4)
        buckets.forEachIndexed { index, (min, max) ->
            val top = center - max.coerceIn(-1f, 1f) * center
            val bottom = center - min.coerceIn(-1f, 1f) * center
            val offset = index * 4
            out[offset] = index * slot
            out[offset + 1] = top
            out[offset + 2] = barWidth
            out[offset + 3] = (bottom - top).coerceAtLeast(1f)
        }
        return out
    }

    private val EMPTY_FLOATS = FloatArray(0)
}

/** Anteil der Hoehe fuer die gespiegelte Reflexion unter der Grundlinie. */
private const val REFLECTION_RATIO = 0.35f

/** Stauchung der Reflexion gegenueber der Hauptwellenform (Poweramp-Optik). */
private const val REFLECTION_SCALE = 0.42f

/** Grundstaerke der Reflexion direkt unter der Grundlinie (blendet nach unten aus). */
private const val REFLECTION_ALPHA = 0.30f

/** Glaettungsdauer des Fortschritts zwischen den 200ms-Ticks (weicher Lauf). */
private const val PROGRESS_SMOOTH_MS = 240

/** Maximaler Abstand (als Anteil der Breite) zum Start des Marker-Drags. */
private const val MARKER_DRAG_SLOP = 0.03f

/**
 * Interaktive Waveform. [buckets] sind Min/Max-Paare in [-1..1];
 * [progressFraction] ist der gespielte Anteil [0..1]. Tap springt sofort
 * ([onSeek]); Drag meldet eine Live-Vorschau ueber [onScrubPreview] und
 * springt erst beim Loslassen — keine seekTo-Flut waehrend der Geste.
 * [markerFractions] zeichnet vorhandene Marker als duenne Ticks in
 * Akzentfarbe (Phase 4); Long-Press meldet die Position an [onLongPress]
 * (Marker setzen bzw. nahe eines Ticks loeschen — der Aufrufer entscheidet).
 * [onMoveMarker] verschiebt einen Marker: beginnt die Geste an einem
 * Marker-Tick, wird die Position laufend gemeldet statt zu scrubbenn.
 *
 * Poweramp-Optik: die Hauptwellenform blendet beim Erscheinen sanft ein, der
 * Fortschritt gleitet weich zwischen den Ticks, und unter der Grundlinie liegt
 * eine gestauchte, nach unten ausblendende Spiegelung.
 */
@Composable
fun Waveform(
    buckets: List<Pair<Float, Float>>,
    progressFraction: Float,
    onSeek: (Float) -> Unit,
    onScrubPreview: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    markerFractions: List<Float> = emptyList(),
    onLongPress: ((Float) -> Unit)? = null,
    onMoveMarker: ((Float) -> Unit)? = null,
    contentDescription: String? = null,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.outlineVariant
    val markerColor = MaterialTheme.colorScheme.tertiary
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    // Drag-Modus: true, sobald die Geste an einem Marker-Tick startet.
    // Dann wird der Marker gezogen statt gescrubbt (Phase 5 "verschiebbar").
    var draggingMarker by remember { mutableStateOf(false) }

    // Sanfter Auftritt: die Wellenform blendet ein, sobald die Analyse vorliegt.
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val appear by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing),
        label = "waveform_appear",
    )
    // Fortschritt gleitet weich zwischen den 200ms-Ticks; beim Scrubben zeigt die
    // Geste sofort die Zielposition (kein Glaetten waehrend des Ziehens).
    val animatedProgress by animateFloatAsState(
        targetValue = progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = PROGRESS_SMOOTH_MS, easing = LinearEasing),
        label = "waveform_progress",
    )

    val shownFraction =
        if (scrubFraction >= 0f) scrubFraction else animatedProgress.coerceIn(0f, 1f)
    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics {
                this.contentDescription = desc
                // A11y (Phase 8/9): Fortschritt als 0..100-Range + gesprochene
                // Prozentzahl, damit TalkBack die Position ohne Slider mitbekommt.
                this.progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = (shownFraction * 100f).roundToInt().coerceIn(0, 100).toFloat(),
                        range = 0f..100f,
                        steps = 0,
                    )
                this.stateDescription = "${(shownFraction * 100f).roundToInt()}%"
            }
        } else {
            modifier
        }
    Canvas(
        modifier =
            semanticsModifier
                .pointerInput(onLongPress != null) {
                    detectTapGestures(
                        onTap = { offset ->
                            onSeek(WaveformMapping.fractionAt(offset.x, size.width.toFloat()))
                        },
                        onLongPress =
                            onLongPress?.let { callback ->
                                { offset: Offset ->
                                    callback(WaveformMapping.fractionAt(offset.x, size.width.toFloat()))
                                }
                            },
                    )
                }.pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val fraction = WaveformMapping.fractionAt(offset.x, size.width.toFloat())
                            // Geste an einem Marker-Tick -> Marker ziehen (nur
                            // wenn der Aufrufer das Verschieben erlaubt).
                            draggingMarker =
                                onMoveMarker != null &&
                                markerFractions.any {
                                    abs(it - fraction) <= MARKER_DRAG_SLOP
                                }
                            if (draggingMarker) {
                                onMoveMarker?.invoke(fraction)
                            } else {
                                scrubFraction = fraction
                                onScrubPreview(scrubFraction)
                            }
                        },
                        onDragEnd = {
                            if (draggingMarker) {
                                draggingMarker = false
                            } else if (scrubFraction >= 0f) {
                                onSeek(scrubFraction)
                            }
                            scrubFraction = -1f
                            onScrubPreview(null)
                        },
                        onDragCancel = {
                            draggingMarker = false
                            scrubFraction = -1f
                            onScrubPreview(null)
                        },
                    ) { change, _ ->
                        val fraction = WaveformMapping.fractionAt(change.position.x, size.width.toFloat())
                        if (draggingMarker) {
                            onMoveMarker?.invoke(fraction)
                        } else {
                            scrubFraction = fraction
                            onScrubPreview(scrubFraction)
                        }
                    }
                },
    ) {
        // Oberer Bereich traegt die Hauptwellenform, darunter liegt die Reflexion.
        val gap = 2.dp.toPx()
        val mainHeight = size.height * (1f - REFLECTION_RATIO)
        // Flaches Float-Array statt Objektliste: keine Allokation im
        // Zeichenpfad (Wissensdoku Abschnitt 25, Scrubbing-Performance).
        val bars = WaveformMapping.toFlatBars(buckets, size.width, mainHeight)
        if (bars.isEmpty()) return@Canvas

        val playedX = shownFraction * size.width
        val corner = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        val baselineY = mainHeight
        val reflectionTop = baselineY + gap

        // Vertikaler Alpha-Verlauf der Reflexion: unter der Grundlinie am
        // kraeftigsten, nach unten hin ausblendend (gemeinsam fuer alle Balken).
        val fade = REFLECTION_ALPHA * appear
        val playedReflection =
            Brush.verticalGradient(
                colors = listOf(playedColor.copy(alpha = fade), Color.Transparent),
                startY = reflectionTop,
                endY = size.height,
            )
        val restReflection =
            Brush.verticalGradient(
                colors = listOf(restColor.copy(alpha = fade), Color.Transparent),
                startY = reflectionTop,
                endY = size.height,
            )

        var offset = 0
        while (offset < bars.size) {
            val left = bars[offset]
            val top = bars[offset + 1]
            val barWidth = bars[offset + 2]
            val barHeight = bars[offset + 3]
            val played = left + barWidth / 2f <= playedX
            val color = if (played) playedColor else restColor
            // Hauptbalken (blendet ueber [appear] ein).
            drawRoundRect(
                color = color.copy(alpha = appear),
                topLeft = Offset(left, top),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
            // Reflexion: gestauchter Balken, an der Grundlinie haengend.
            drawRoundRect(
                brush = if (played) playedReflection else restReflection,
                topLeft = Offset(left, reflectionTop),
                size = Size(barWidth, barHeight * REFLECTION_SCALE),
                cornerRadius = corner,
            )
            offset += 4
        }
        // Marker-Ticks (Phase 4): duenne Linien in Akzentfarbe im Hauptbereich.
        markerFractions.forEach { fraction ->
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawLine(
                color = markerColor.copy(alpha = appear),
                start = Offset(x, 0f),
                end = Offset(x, baselineY),
                strokeWidth = 2.dp.toPx(),
            )
        }
        // Positionslinie als klarer Anker der Bedienflaeche (ueber beide Zonen).
        drawLine(
            color = playedColor.copy(alpha = appear),
            start = Offset(playedX, 0f),
            end = Offset(playedX, size.height),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/**
 * Ruhiger Ladeplatzhalter, solange die Analyse laeuft (Plan Phase 3):
 * gedaempft pulsierender Balken statt eines leeren Bereichs.
 */
@Composable
fun WaveformPlaceholder(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val color = MaterialTheme.colorScheme.outlineVariant
    val transition = rememberInfiniteTransition(label = "waveform_placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "waveform_placeholder_alpha",
    )
    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics { this.contentDescription = desc }
        } else {
            modifier
        }
    Canvas(modifier = semanticsModifier) {
        val barHeight = size.height * 0.25f
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(0f, (size.height - barHeight) / 2f),
            size = Size(size.width, barHeight),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
    }
}

/**
 * Nicht-interaktive Mini-Waveform fuer Listen (Library, Phase 8):
 * reine Optik ohne Gesten/Ticker. [buckets] sind Min/Max-Paare in
 * [-1..1]; [progressFraction] faerbt den gespielten Anteil Lime.
 * Keine Analyse vorhanden -> Aufrufer zeigt nichts bzw. einen
 * Platzhalter; die Zeile bleibt so schlank.
 */
@Composable
fun MiniWaveform(
    buckets: List<Pair<Float, Float>>,
    progressFraction: Float,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val playedColor = MaterialTheme.colorScheme.primary
    val restColor = MaterialTheme.colorScheme.outlineVariant
    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics { this.contentDescription = desc }
        } else {
            modifier
        }
    Canvas(modifier = semanticsModifier) {
        if (buckets.isEmpty()) return@Canvas
        val bars = WaveformMapping.toFlatBars(buckets, size.width, size.height, gapFraction = 0.35f)
        if (bars.isEmpty()) return@Canvas
        val playedX = progressFraction.coerceIn(0f, 1f) * size.width
        val corner = CornerRadius(1.dp.toPx(), 1.dp.toPx())
        var offset = 0
        while (offset < bars.size) {
            val left = bars[offset]
            val played = left + bars[offset + 2] / 2f <= playedX
            drawRoundRect(
                color = if (played) playedColor else restColor,
                topLeft = Offset(left, bars[offset + 1]),
                size = Size(bars[offset + 2], bars[offset + 3]),
                cornerRadius = corner,
            )
            offset += 4
        }
    }
}
