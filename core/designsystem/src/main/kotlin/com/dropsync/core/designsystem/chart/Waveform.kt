package com.dropsync.core.designsystem.chart

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.dropsync.core.designsystem.theme.rememberReducedMotion
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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

/**
 * Vorbereitete Waveform-Geometrie (Offtrack Phase 9, Abschnitt 12.1/12.2):
 * die normalisierten Buckets werden einmal als unveraenderliches Modell
 * gecacht; der Canvas rechnet nur noch die aktuelle Breite/Hoehe in das
 * flache Float-Array um. Dadurch entstehen im Zeichenpfad keine
 * Objekt-Allokationen je Bucket und kein Decoder-/DB-Zugriff.
 */
data class WaveformGeometry(
    val buckets: List<Pair<Float, Float>>,
) {
    /** Flache Balken fuer die konkrete Canvas-Groesse (0,0 = leer). */
    fun bars(
        width: Float,
        height: Float,
        gapFraction: Float,
    ): FloatArray = WaveformMapping.toFlatBars(buckets, width, height, gapFraction)

    companion object {
        val EMPTY = WaveformGeometry(emptyList())
    }
}

/** Glaettungsdauer des Fortschritts zwischen den 200ms-Ticks (weicher Lauf). */
private const val PROGRESS_SMOOTH_MS = 240

/**
 * Sprungweite, ab der der Fortschritt hart gesetzt statt geglaettet wird.
 * Ein Seek oder Titelwechsel soll sofort stehen, nicht ueber den Track
 * kriechen.
 */
private const val PROGRESS_SNAP_THRESHOLD = 0.05f

/** Maximaler Abstand (als Anteil der Breite) zum Start des Marker-Drags. */
private const val MARKER_DRAG_SLOP = 0.03f

/**
 * Trefferzone fuer den Marker-Tap (P2-21, UI-Handbuch 14.4): etwas
 * grosszuegiger als der Drag-Slop, damit der Finger den duennen Tick
 * sicher trifft, ohne das normale Seek-Tippen zu verschlucken.
 */
private const val MARKER_TAP_SLOP = 0.04f

/** Radius des Ziel-Diamanten (P2-21) in dp. */
private val MARKER_TARGET_RADIUS = 4.dp

/** Deckkraft der Vorschlags-Ticks relativ zur Markerfarbe (P2-21). */
private const val SUGGESTION_ALPHA = 0.45f

/**
 * Breite der Marker-Ticks: 3 dp bleiben optisch duenn, zusammen mit der
 * Trefferzone des Aufrufers (~24 dp) entsteht ein ~48-dp-Bedienziel
 * (A11y-Mindestgroesse, Recherche 2026).
 */
private val MARKER_TICK_WIDTH = 3.dp

/**
 * Geglaetteter Fortschritt als [State], **ohne** die Composition zu
 * invalidieren (P1-Fix).
 *
 * Vorher stand hier `val x by animateFloatAsState(...)`. Das `by` liest den
 * Animationswert in der COMPOSITION: der Animator schreibt pro Frame, die
 * Composable recomposed also mit ~60 Hz — dauerhaft, weil der 200-ms-Ticker
 * immer wieder ein neues Ziel setzt und die 240-ms-Animation nie zur Ruhe
 * kommt. Betroffen war der komplette Screen-Body (Cover-Pager, Titelzeile,
 * Transport), nicht nur die Wellenform.
 *
 * Der zurueckgegebene State wird ausschliesslich im `Canvas`-Zeichenblock
 * gelesen. Ein Draw-Phase-Read invalidiert nur die Draw-Phase — Composition
 * und Layout bleiben stehen (Compose-Phasen-Modell, offizielle
 * Performance-Leitlinie "defer state reads").
 */
@Composable
private fun rememberSmoothedFraction(
    progressFraction: () -> Float,
    reducedMotion: Boolean = false,
): State<Float> {
    val animatable = remember { Animatable(progressFraction().coerceIn(0f, 1f)) }
    LaunchedEffect(animatable, reducedMotion) {
        snapshotFlow { progressFraction().coerceIn(0f, 1f) }
            .collectLatest { goal ->
                if (reducedMotion || abs(goal - animatable.value) > PROGRESS_SNAP_THRESHOLD) {
                    animatable.snapTo(goal)
                } else {
                    animatable.animateTo(
                        targetValue = goal,
                        animationSpec = tween(durationMillis = PROGRESS_SMOOTH_MS, easing = LinearEasing),
                    )
                }
            }
    }
    return animatable.asState()
}

/**
 * Einblend-Deckkraft als [State] (gleiche Begruendung wie
 * [rememberSmoothedFraction]: Draw-Phase statt Composition).
 */
@Composable
private fun rememberAppearAlpha(durationMs: Int): State<Float> {
    val animatable = remember { Animatable(if (durationMs <= 0) 1f else 0f) }
    LaunchedEffect(animatable) {
        if (durationMs > 0) {
            animatable.animateTo(1f, tween(durationMillis = durationMs, easing = FastOutSlowInEasing))
        }
    }
    return animatable.asState()
}

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
 * Fortschritt gleitet weich zwischen den Ticks. Die Balken werden als
 * vorbereitete [WaveformGeometry] gezeichnet und der gespielte Anteil ueber
 * einen Clip eingefaerbt (keine zweite, frische Path-Allokation).
 *
 * [progressFraction] ist bewusst eine **Lambda**, kein Wert (P1-Fix): der
 * Aufrufer liest die Position damit nicht in seiner eigenen Composition,
 * sondern erst hier im Zeichenblock. Ein 200-ms-Ticker invalidiert so nur
 * noch die Draw-Phase dieser Komponente statt des ganzen Screens.
 */
@Composable
fun Waveform(
    buckets: List<Pair<Float, Float>>,
    progressFraction: () -> Float,
    onSeek: (Float) -> Unit,
    onScrubPreview: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    markerFractions: List<Float> = emptyList(),
    onLongPress: ((Float) -> Unit)? = null,
    onMoveMarker: ((Float) -> Unit)? = null,
    contentDescription: String? = null,
    playedColor: Color? = null,
    restColor: Color? = null,
    markerColor: Color? = null,
    gapFraction: Float = 0.15f,
    markerDragSlopFraction: Float = MARKER_DRAG_SLOP,
) {
    val resolvedPlayedColor = playedColor ?: MaterialTheme.colorScheme.primary
    // Rest-Balken mit deutlich hoeherem Kontrast als outlineVariant:
    // im Light-Mode waere #EAEAEA auf Weiss fast unsichtbar (P0-Befund).
    val resolvedRestColor = restColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val resolvedMarkerColor = markerColor ?: MaterialTheme.colorScheme.tertiary
    // Vorbereitete Geometrie: nur bei neuen Buckets neu aufbauen, nie je
    // Draw-Frame (Offtrack Phase 9).
    val geometry = remember(buckets) { WaveformGeometry(buckets) }
    // Scrub-Zustand als MutableFloatState: wird ausschliesslich in der
    // Draw-Phase gelesen, deshalb kein `by`-Delegat in der Composition.
    val scrubState = remember { mutableFloatStateOf(-1f) }
    // Drag-Modus: true, sobald die Geste an einem Marker-Tick startet.
    // Dann wird der Marker gezogen statt gescrubbt (Phase 5 "verschiebbar").
    var draggingMarker by remember { mutableStateOf(false) }

    // Reduced Motion (7.2/6): ohne Animationen blendet die Wellenform sofort
    // voll ein und der Fortschritt springt ohne Glaettung.
    val reducedMotion = rememberReducedMotion()
    val appear = rememberAppearAlpha(if (reducedMotion) 0 else APPEAR_MS)
    val smoothed = rememberSmoothedFraction(progressFraction, reducedMotion)

    // Ein Lesevorgang, den BEIDE Phasen brauchen: Semantik (Composition) und
    // Zeichnen (Draw). Die Semantik nimmt bewusst den ungeglaetteten Wert —
    // TalkBack braucht keine 60-Hz-Aktualisierung, und ein Composition-Read
    // des Animators waere genau der Recomposition-Sturm, der hier vermieden
    // werden soll.
    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics {
                this.contentDescription = desc
                // A11y (Phase 8/9): Fortschritt als 0..100-Range + gesprochene
                // Prozentzahl, damit TalkBack die Position ohne Slider mitbekommt.
                val percent = (progressFraction().coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
                this.progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = percent.toFloat(),
                        range = 0f..100f,
                        steps = 0,
                    )
                this.stateDescription = "$percent%"
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
                                    abs(it - fraction) <= markerDragSlopFraction
                                }
                            if (draggingMarker) {
                                onMoveMarker?.invoke(fraction)
                            } else {
                                scrubState.floatValue = fraction
                                onScrubPreview(fraction)
                            }
                        },
                        onDragEnd = {
                            val scrub = scrubState.floatValue
                            if (draggingMarker) {
                                draggingMarker = false
                            } else if (scrub >= 0f) {
                                onSeek(scrub)
                            }
                            scrubState.floatValue = -1f
                            onScrubPreview(null)
                        },
                        onDragCancel = {
                            draggingMarker = false
                            scrubState.floatValue = -1f
                            onScrubPreview(null)
                        },
                    ) { change, _ ->
                        val fraction = WaveformMapping.fractionAt(change.position.x, size.width.toFloat())
                        if (draggingMarker) {
                            onMoveMarker?.invoke(fraction)
                        } else {
                            scrubState.floatValue = fraction
                            onScrubPreview(fraction)
                        }
                    }
                },
    ) {
        // Volle Hoehe fuer die Balken: keine Reflexion (die Referenz-Optik
        // zeigt nur eine Balkenlinie, kein Spiegelbild darunter).
        val bars = geometry.bars(size.width, size.height, gapFraction)
        if (bars.isEmpty()) return@Canvas

        // Alle State-Reads liegen im Zeichenblock (Draw-Phase).
        val scrub = scrubState.floatValue
        val shownFraction = if (scrub >= 0f) scrub else smoothed.value.coerceIn(0f, 1f)
        val alpha = appear.value

        val playedX = shownFraction * size.width
        val corner = CornerRadius(1.dp.toPx(), 1.dp.toPx())

        // Gespielter Anteil per Clip: die Rest-Balken werden einmal gezeichnet
        // und nur der linke Bereich in Akzentfarbe ueberlagert (12.5). Kein
        // zweiter, komplett neuer Path je Frame.
        drawWaveformBars(
            bars = bars,
            color = resolvedRestColor,
            alpha = alpha,
            corner = corner,
        )
        clipRect(right = playedX) {
            drawWaveformBars(
                bars = bars,
                color = resolvedPlayedColor,
                alpha = alpha,
                corner = corner,
            )
        }
        // Marker-Ticks (Phase 4): duenne Linien in Akzentfarbe im Hauptbereich.
        markerFractions.forEach { fraction ->
            val x = fraction.coerceIn(0f, 1f) * size.width
            drawLine(
                color = resolvedMarkerColor.copy(alpha = alpha),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = MARKER_TICK_WIDTH.toPx(),
            )
        }
    }
}

/** Einblenddauer der Uebersichts-Waveform. */
private const val APPEAR_MS = 480

/** Zeichnet vorbereitete Balken mit einer gemeinsamen Farbe und Deckkraft. */
private fun DrawScope.drawWaveformBars(
    bars: FloatArray,
    color: Color,
    alpha: Float,
    corner: CornerRadius,
) {
    var offset = 0
    while (offset < bars.size) {
        val left = bars[offset]
        val top = bars[offset + 1]
        val barWidth = bars[offset + 2]
        val barHeight = bars[offset + 3]
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(barWidth, barHeight),
            cornerRadius = corner,
        )
        offset += 4
    }
}

/**
 * Reine Mathematik der laufenden Waveform ("Waveseek"-Prinzip), getrennt
 * von Compose und damit deterministisch testbar
 * (RunningWaveformMappingTest).
 *
 * Grundgedanke aus der Poweramp-Analyse (`Waveseek` erbt `i5`):
 * die Track-Geometrie liegt als **fester** Balkenraster vor
 * (`i5.K` Peaks, Balkenbreite `H`, Luecke `P`, Gesamtbreite
 * `w = (H + P) * K - P`). Gezeichnet wird nicht neu abgetastet, sondern
 * der Raster wird um einen Offset verschoben und beschnitten
 * (`i5.onDraw`: `firstIndex`/`lastIndex` aus Offset und Rasterweite,
 * `clipRect`, Teil-Alpha an den Raendern). Der Playhead bleibt an einer
 * festen Position (`i5.o`, Default 0.5 = Mitte).
 */
object RunningWaveformMapping {
    /**
     * Baut die stabile Track-Geometrie: Min/Max-Buckets werden **einmal**
     * auf [targetSize] Amplituden in [0..1] umgerechnet (linear
     * interpoliert, damit grobe 256er-Analysen einen feinen Raster
     * ergeben). Das Ergebnis ist unabhaengig von Fortschritt und
     * Canvas-Groesse und darf gecacht werden.
     */
    fun amplitudes(
        buckets: List<Pair<Float, Float>>,
        targetSize: Int,
    ): FloatArray {
        if (buckets.isEmpty() || targetSize <= 0) return FloatArray(0)
        val source =
            FloatArray(buckets.size) { index ->
                val (min, max) = buckets[index]
                maxOf(abs(min), abs(max)).coerceIn(0f, 1f)
            }
        if (source.size == 1) return FloatArray(targetSize) { source[0] }
        return FloatArray(targetSize) { index ->
            val position = index * (source.size - 1f) / (targetSize - 1f).coerceAtLeast(1f)
            val low = position.toInt().coerceIn(0, source.size - 1)
            val high = (low + 1).coerceAtMost(source.size - 1)
            val t = position - low
            source[low] + (source[high] - source[low]) * t
        }
    }

    /**
     * Rasterweite eines Balkens in Pixeln: der sichtbare Ausschnitt
     * ([viewportFraction] des Tracks) fuellt genau [width]. Damit bleibt
     * die Balkenbreite konstant, waehrend der Raster scrollt.
     */
    fun pitch(
        width: Float,
        barCount: Int,
        viewportFraction: Float,
    ): Float {
        if (width <= 0f || barCount <= 0) return 0f
        val visibleBars = (barCount * viewportFraction.coerceIn(0.02f, 1f)).coerceAtLeast(1f)
        return width / visibleBars
    }

    /** Gesamtbreite des virtuellen Rasters (Poweramp `i5.w`). */
    fun virtualWidth(
        barCount: Int,
        pitch: Float,
    ): Float = if (barCount <= 0) 0f else barCount * pitch

    /**
     * Scroll-Offset fuer [fraction]: der Playhead sitzt fest bei
     * `width * playheadFraction`; der Raster wandert darunter durch. Am
     * Track-Anfang/-Ende wird nicht geklemmt, damit die Bewegung linear
     * mit der Zeit bleibt (Poweramp zeigt dort Leerraum).
     */
    fun scrollX(
        fraction: Float,
        virtualWidth: Float,
        width: Float,
        playheadFraction: Float,
    ): Float = fraction.coerceIn(0f, 1f) * virtualWidth - width * playheadFraction.coerceIn(0f, 1f)

    /** Erster sichtbarer Balkenindex (kann negativ sein -> nichts zeichnen). */
    fun firstVisibleIndex(
        scrollX: Float,
        pitch: Float,
    ): Int = if (pitch <= 0f) 0 else floor(scrollX / pitch).toInt()

    /** Letzter sichtbarer Balkenindex (inklusive). */
    fun lastVisibleIndex(
        scrollX: Float,
        width: Float,
        pitch: Float,
    ): Int = if (pitch <= 0f) 0 else ceil((scrollX + width) / pitch).toInt()

    /**
     * X-Position eines Balkens im Canvas (linke Kante des Rasterslots).
     */
    fun barLeft(
        index: Int,
        pitch: Float,
        scrollX: Float,
    ): Float = index * pitch - scrollX

    /**
     * Track-Anteil [0..1] an der Canvas-Position [x] — Umkehrung von
     * [scrollX]. Grundlage fuer Tap-to-Seek: der angetippte Balken wird
     * zum neuen Playhead.
     */
    fun fractionAtX(
        x: Float,
        scrollX: Float,
        virtualWidth: Float,
    ): Float = if (virtualWidth <= 0f) 0f else ((scrollX + x) / virtualWidth).coerceIn(0f, 1f)

    /**
     * Anteilsverschiebung fuer eine horizontale Drag-Strecke [dx]:
     * die Welle wird unter dem Playhead durchgezogen (Poweramp zieht den
     * Raster, nicht den Zeiger). Nach links ziehen laeuft vorwaerts.
     */
    fun fractionDelta(
        dx: Float,
        virtualWidth: Float,
    ): Float = if (virtualWidth <= 0f) 0f else -dx / virtualWidth

    /**
     * Randabblendung: Balken am linken/rechten Rand laufen weich aus
     * (Poweramp berechnet in `onDraw` fuer die Teil-Balken ein Alpha
     * 0..255 aus der Ueberdeckung). [fadeWidth] ist die Breite der
     * Blende in Pixeln.
     */
    fun edgeAlpha(
        x: Float,
        width: Float,
        fadeWidth: Float,
    ): Float {
        if (fadeWidth <= 0f || width <= 0f) return 1f
        val fromLeft = x / fadeWidth
        val fromRight = (width - x) / fadeWidth
        return minOf(fromLeft, fromRight, 1f).coerceIn(0f, 1f)
    }
}

/**
 * Laufende Waveform im Poweramp-Stil ("Waveseek").
 *
 * Anders als eine Uebersichts-Seekbar zeigt sie **einen Ausschnitt** des
 * Tracks: der Balkenraster ist fest an die Trackzeit gebunden und wandert
 * waehrend der Wiedergabe unter einem ortsfesten Playhead
 * ([playheadFraction], Default Mitte) hindurch. Die Form wird einmal je
 * Bucket-Satz vorbereitet ([RunningWaveformMapping.amplitudes]) und beim
 * Zeichnen nur verschoben — keine erneute Abtastung je Frame, deshalb
 * bleibt die Wellenform musikalisch stabil.
 *
 * Bedienung wie im Original: Tap setzt den angetippte Balken auf den
 * Playhead, Drag zieht die Welle durch (Vorschau ueber [onScrubPreview]),
 * der Sprung wird erst beim Loslassen committet, Abbruch verwirft ihn.
 *
 * [progressFraction] ist eine Lambda (P1-Fix, siehe [Waveform]): die Position
 * wird erst im Zeichenblock gelesen, damit der 200-ms-Ticker nicht die
 * Composition des ganzen Player-Screens invalidiert.
 *
 * P2-21: [suggestionFractions] zeichnet unbestaetigte Onset-Vorschlaege als
 * gedaempfte Ticks, [targetFraction] markiert das bevorzugte DropSync-Ziel
 * mit einem Diamanten ueber dem Tick. Ist [onMarkerTap] gesetzt, gewinnt der
 * Marker-Tap gegen den Seek — ein Tipp in die Trefferzone oeffnet das
 * Marker-Sheet statt zu springen (UI-Handbuch 14.4).
 */
@Composable
fun RunningWaveform(
    buckets: List<Pair<Float, Float>>,
    progressFraction: () -> Float,
    onSeek: (Float) -> Unit,
    onScrubPreview: (Float?) -> Unit,
    modifier: Modifier = Modifier,
    markerFractions: List<Float> = emptyList(),
    onLongPress: ((Float) -> Unit)? = null,
    onMoveMarker: ((Float) -> Unit)? = null,
    suggestionFractions: List<Float> = emptyList(),
    targetFraction: Float? = null,
    onMarkerTap: ((Float) -> Unit)? = null,
    markerTapSlopFraction: Float = MARKER_TAP_SLOP,
    contentDescription: String? = null,
    playedColor: Color = Color(0xFF009FE3),
    upcomingColor: Color = Color(0xFF65C0E4),
    reflectionColor: Color = Color(0xFFD9F0F8),
    markerColor: Color = Color(0xFF009FE3),
    viewportFraction: Float = 0.22f,
    playheadFraction: Float = 0.5f,
    barCount: Int = RUNNING_WAVEFORM_BARS,
) {
    val safeViewport = viewportFraction.coerceIn(0.02f, 1f)

    // Stabile Track-Geometrie: nur bei neuen Buckets neu aufbauen. Genau
    // das ist der Poweramp-Punkt — `Waveseek.x(f0)` liest das vorbereitete
    // Float-Array aus dem Playerzustand statt es je Frame zu erzeugen.
    val amplitudes =
        remember(buckets, barCount) {
            RunningWaveformMapping.amplitudes(buckets, barCount)
        }

    // Scrub-Zustand: -1 = keine Geste. Waehrend des Ziehens gilt die
    // Vorschau, der echte seekTo folgt erst beim Loslassen. Als
    // MutableFloatState, damit die Gesten schreiben und nur die Draw-Phase
    // liest (kein Composition-Read).
    val scrubState = remember { mutableFloatStateOf(-1f) }
    var draggingMarker by remember { mutableStateOf(false) }

    // Zwischen den 200ms-Positionsticks weich gleiten, damit der Raster
    // sichtbar laeuft statt zu springen (Poweramp interpoliert dafuer in
    // `q1.doFrame` zeitbasiert je Frame). Reduced Motion (7.2/6): springt
    // sofort statt zu gleiten, blendet ohne Animation ein.
    val reducedMotion = rememberReducedMotion()
    val smoothed = rememberSmoothedFraction(progressFraction, reducedMotion)
    val appearance = rememberAppearAlpha(if (reducedMotion) 0 else RUNNING_APPEAR_MS)

    /** Aktuell gezeigter Anteil: Scrub-Vorschau hat Vorrang. */
    fun shownFraction(): Float {
        val scrub = scrubState.floatValue
        return if (scrub >= 0f) scrub else smoothed.value.coerceIn(0f, 1f)
    }

    val desc = contentDescription
    val semanticsModifier =
        if (desc != null) {
            modifier.semantics {
                this.contentDescription = desc
                val percent = (progressFraction().coerceIn(0f, 1f) * 100f).roundToInt().coerceIn(0, 100)
                progressBarRangeInfo =
                    ProgressBarRangeInfo(
                        current = percent.toFloat(),
                        range = 0f..100f,
                        steps = 0,
                    )
                stateDescription = "$percent%"
                // C9 (P-8, UI-Handbuch 19.4): Slider-Fallback — TalkBack
                // kann den Fortschritt setzen ("nach rechts wischen"), auch
                // ohne Zeigergeste. Der Wert kommt in Prozent (0..100).
                setProgress { target ->
                    onSeek((target / 100f).coerceIn(0f, 1f))
                    true
                }
            }
        } else {
            modifier
        }

    // Geometrie der laufenden Gesten. P0-Fix (Stale Closure): die
    // pointerInput-Bloecke werden nur bei Aenderung ihrer Keys neu gestartet,
    // NICHT bei jedem Fortschritts-Tick. Ein eingefrorener Positionswert
    // haette Tap-to-Seek mit wachsender Spielzeit immer weiter danebenlanden
    // lassen. `shownFraction()` liest den State jetzt bei jedem Aufruf frisch.
    fun scrollFor(
        fraction: Float,
        width: Float,
    ): Float {
        val pitch = RunningWaveformMapping.pitch(width, amplitudes.size, safeViewport)
        val virtual = RunningWaveformMapping.virtualWidth(amplitudes.size, pitch)
        return RunningWaveformMapping.scrollX(fraction, virtual, width, playheadFraction)
    }

    fun globalFractionAt(
        x: Float,
        width: Float,
    ): Float {
        val pitch = RunningWaveformMapping.pitch(width, amplitudes.size, safeViewport)
        val virtual = RunningWaveformMapping.virtualWidth(amplitudes.size, pitch)
        return RunningWaveformMapping.fractionAtX(x, scrollFor(shownFraction(), width), virtual)
    }

    Canvas(
        modifier =
            semanticsModifier
                .pointerInput(
                    amplitudes,
                    onLongPress != null,
                    onMarkerTap != null,
                    markerFractions,
                    suggestionFractions,
                ) {
                    detectTapGestures(
                        onTap = { offset ->
                            val width = size.width.toFloat()
                            val fraction = globalFractionAt(offset.x, width)
                            // P2-21: erst der Marker-Tap, dann der Seek.
                            val markerHit =
                                onMarkerTap != null &&
                                    WaveformMapping.nearestMarkerIndex(
                                        markerFractions + suggestionFractions,
                                        fraction,
                                        markerTapSlopFraction,
                                    ) >= 0
                            if (markerHit && onMarkerTap != null) {
                                onMarkerTap.invoke(fraction)
                            } else {
                                onSeek(fraction)
                            }
                        },
                        onLongPress =
                            onLongPress?.let { callback ->
                                { offset: Offset ->
                                    callback(globalFractionAt(offset.x, size.width.toFloat()))
                                }
                            },
                    )
                }.pointerInput(amplitudes) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            val width = size.width.toFloat()
                            val fraction = globalFractionAt(offset.x, width)
                            // Geste an einem sichtbaren Marker-Tick -> Marker
                            // ziehen statt scrubben (der Aufrufer entscheidet,
                            // ob Verschieben erlaubt ist).
                            draggingMarker =
                                onMoveMarker != null &&
                                markerFractions.any { abs(it - fraction) <= MARKER_DRAG_SLOP }
                            if (draggingMarker) {
                                onMoveMarker?.invoke(fraction)
                            } else {
                                scrubState.floatValue = fraction
                                onScrubPreview(fraction)
                            }
                        },
                        onDragEnd = {
                            val scrub = scrubState.floatValue
                            if (draggingMarker) {
                                draggingMarker = false
                            } else if (scrub >= 0f) {
                                onSeek(scrub)
                            }
                            scrubState.floatValue = -1f
                            onScrubPreview(null)
                        },
                        onDragCancel = {
                            // Abbruch darf nichts committen (Poweramp trennt
                            // UP und CANCEL, `Seek.smali`).
                            draggingMarker = false
                            scrubState.floatValue = -1f
                            onScrubPreview(null)
                        },
                    ) { change, dragAmount ->
                        val width = size.width.toFloat()
                        if (draggingMarker) {
                            onMoveMarker?.invoke(globalFractionAt(change.position.x, width))
                        } else {
                            // Relatives Ziehen: die Welle laeuft unter dem
                            // ortsfesten Playhead durch.
                            val pitch = RunningWaveformMapping.pitch(width, amplitudes.size, safeViewport)
                            val virtual = RunningWaveformMapping.virtualWidth(amplitudes.size, pitch)
                            val next =
                                (scrubState.floatValue + RunningWaveformMapping.fractionDelta(dragAmount, virtual))
                                    .coerceIn(0f, 1f)
                            scrubState.floatValue = next
                            onScrubPreview(next)
                        }
                    }
                },
    ) {
        if (amplitudes.isEmpty()) return@Canvas
        val width = size.width
        val pitch = RunningWaveformMapping.pitch(width, amplitudes.size, safeViewport)
        if (pitch <= 0f) return@Canvas
        // Alle State-Reads in der Draw-Phase.
        val currentFraction = shownFraction()
        val appear = appearance.value
        val virtual = RunningWaveformMapping.virtualWidth(amplitudes.size, pitch)
        val scroll = RunningWaveformMapping.scrollX(currentFraction, virtual, width, playheadFraction)

        // Vertikale Aufteilung wie in `i5.onLayout`: Hauptbereich oben,
        // Luecke, gespiegelter blasser Bereich darunter.
        val topHeight = size.height * TOP_SHARE
        val gap = size.height * MIRROR_GAP_SHARE
        val baseline = topHeight
        val mirrorHeight = size.height * MIRROR_SHARE

        val barWidth = (pitch * BAR_WIDTH_SHARE).coerceAtLeast(1.5f)
        val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
        val fade = pitch * EDGE_FADE_BARS
        val playheadX = width * playheadFraction.coerceIn(0f, 1f)

        val first = RunningWaveformMapping.firstVisibleIndex(scroll, pitch).coerceAtLeast(0)
        val last = RunningWaveformMapping.lastVisibleIndex(scroll, width, pitch).coerceAtMost(amplitudes.size - 1)
        if (last < first) return@Canvas

        for (index in first..last) {
            val slotLeft = RunningWaveformMapping.barLeft(index, pitch, scroll)
            val left = slotLeft + (pitch - barWidth) / 2f
            val center = slotLeft + pitch / 2f
            val alpha =
                appear * RunningWaveformMapping.edgeAlpha(center, width, fade)
            if (alpha <= 0.01f) continue
            val barHeight = (amplitudes[index] * topHeight).coerceAtLeast(MIN_BAR_PX)
            // Gespielt vs. kommend: zwei Farbstufen wie die beiden
            // Balken-Bitmaps in `i5` (Feld I/J).
            val color = if (center <= playheadX) playedColor else upcomingColor
            drawRoundRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(left, baseline - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = reflectionColor.copy(alpha = alpha),
                topLeft = Offset(left, baseline + gap),
                size = Size(barWidth, (amplitudes[index] * mirrorHeight).coerceAtLeast(MIN_BAR_PX)),
                cornerRadius = corner,
            )
        }

        // Vorschlaege (P2-21, UI-Handbuch 14.3): unbestaetigte Onset-Kandidaten
        // als gedaempfte, duennere Ticks UNTER den aktiven Markern.
        val suggestionStroke = (MARKER_TICK_WIDTH.toPx() * 0.66f).coerceAtLeast(1f)
        suggestionFractions.forEach { suggestion ->
            val x = suggestion.coerceIn(0f, 1f) * virtual - scroll
            if (x in 0f..width) {
                drawLine(
                    color =
                        markerColor.copy(
                            alpha =
                                appear * SUGGESTION_ALPHA *
                                    RunningWaveformMapping.edgeAlpha(x, width, fade),
                        ),
                    start = Offset(x, baseline - topHeight),
                    end = Offset(x, baseline + gap + mirrorHeight),
                    strokeWidth = suggestionStroke,
                )
            }
        }

        // Marker als Overlay, nicht als Teil der Balkenschleife.
        markerFractions.forEach { marker ->
            val x = marker.coerceIn(0f, 1f) * virtual - scroll
            if (x in 0f..width) {
                drawLine(
                    color = markerColor.copy(alpha = appear * RunningWaveformMapping.edgeAlpha(x, width, fade)),
                    start = Offset(x, baseline - topHeight),
                    end = Offset(x, baseline + gap + mirrorHeight),
                    strokeWidth = MARKER_TICK_WIDTH.toPx(),
                )
            }
        }

        // Bevorzugtes DropSync-Ziel (P2-21): Diamant ueber dem Tick, damit
        // das Ziel nicht nur ueber die Legende unterscheidbar ist.
        targetFraction?.let { target ->
            val x = target.coerceIn(0f, 1f) * virtual - scroll
            if (x in 0f..width) {
                val radius = MARKER_TARGET_RADIUS.toPx()
                val centerY = radius + 1.dp.toPx()
                val alpha = appear * RunningWaveformMapping.edgeAlpha(x, width, fade)
                val diamond =
                    Path().apply {
                        moveTo(x, centerY - radius)
                        lineTo(x + radius, centerY)
                        lineTo(x, centerY + radius)
                        lineTo(x - radius, centerY)
                        close()
                    }
                drawPath(path = diamond, color = markerColor.copy(alpha = alpha))
            }
        }
    }
}

/**
 * Feinheit des Rasters: die 256 Analyse-Buckets werden auf so viele
 * Balken interpoliert, dass ein Ausschnitt duenne, regelmaessige Striche
 * zeigt (Referenzoptik) und der Raster ueber den Track sichtbar wandert.
 */
const val RUNNING_WAVEFORM_BARS = 320

/** Anteil der Hoehe fuer die kraeftigen oberen Balken. */
private const val TOP_SHARE = 0.56f

/** Luecke zwischen Hauptbereich und Spiegelung (Poweramp `i5.O`). */
private const val MIRROR_GAP_SHARE = 0.03f

/** Anteil der Hoehe fuer die blasse Spiegelung darunter. */
private const val MIRROR_SHARE = 0.38f

/** Balkenbreite als Anteil der Rasterweite -> duenne Striche mit Luft. */
private const val BAR_WIDTH_SHARE = 0.34f

/** Breite der Randblende, gemessen in Rasterweiten. */
private const val EDGE_FADE_BARS = 3.5f

/** Mindesthoehe, damit stille Stellen nicht als Luecke erscheinen. */
private const val MIN_BAR_PX = 2f

/** Einblenddauer der laufenden Waveform. */
private const val RUNNING_APPEAR_MS = 420

/**
 * Ruhiger Ladeplatzhalter, solange die Analyse laeuft (Plan Phase 3):
 * gedaempft pulsierender Balken statt eines leeren Bereichs.
 */
@Composable
fun WaveformPlaceholder(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // Reduced Motion (7.2/6): statische Deckkraft statt Puls.
    val alpha =
        if (rememberReducedMotion()) {
            0.5f
        } else {
            val transition = rememberInfiniteTransition(label = "waveform_placeholder")
            val animated by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 0.7f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 900, easing = LinearEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                label = "waveform_placeholder_alpha",
            )
            animated
        }
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
    val restColor = MaterialTheme.colorScheme.onSurfaceVariant
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
