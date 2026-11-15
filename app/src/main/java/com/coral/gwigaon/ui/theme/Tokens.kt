package com.coral.gwigaon.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.math.pow

/**
 * DESIGN.md as tokens (D3a). Every colour has one job: purple progress, amber caution, red SOS, green safe.
 * The night palette is DESIGN.md's; the day palette keeps each role and its 4.5:1 text floor (TokensTest).
 */
@Immutable
data class GwigaonPalette(
    val window: Color,
    val sunk: Color,
    val surface: Color,
    val surfaceHigh: Color,
    /** The arrival card's lit surface, top → bottom. */
    val litTop: Color,
    val litBottom: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Dividers between rows (8 %) and inside cards (6 %). */
    val line: Color,
    val lineFaint: Color,
    val progress: Color,
    val caution: Color,
    val cautionText: Color,
    val cautionTint: Color,
    /** A caution mark on the progress bar once the event is over. */
    val cautionPast: Color,
    val sos: Color,
    val sosText: Color,
    val sosGround: Color,
    val sosGroundPressed: Color,
    val sosTrack: Color,
    val sosSentGround: Color,
    val sosSentGlow: Color,
    val sosSentCheck: Color,
    val safe: Color,
    val safeText: Color,
    val safeTint: Color,
    /** The strongest action: the inverse of the window. */
    val primary: Color,
    val onPrimary: Color,
    val onAccent: Color,
    val switchOff: Color,
    val knob: Color,
    /** Icons on the SOS red badge. */
    val onSos: Color,
    /** The SOS-sent screen's time line and its quiet "잘못 눌렀어요" ground. */
    val sosSentDetail: Color,
    val sosSentQuiet: Color,
)

private val Ink = Color(0xFF131417)

val NightPalette = GwigaonPalette(
    window = Color(0xFF1D1E21),
    sunk = Ink,
    surface = Color(0xFF313236),
    surfaceHigh = Color(0xFF44464E),
    litTop = Color(0xFF36373C),
    litBottom = Color(0xFF2C2D31),
    textPrimary = Color.White,
    textSecondary = Color.White.copy(alpha = 0.84f),
    textTertiary = Color.White.copy(alpha = 0.64f),
    line = Color.White.copy(alpha = 0.08f),
    lineFaint = Color.White.copy(alpha = 0.06f),
    progress = Color(0xFF6A57FF),
    caution = Color(0xFFFFA202),
    cautionText = Color(0xFFFFB62E),
    cautionTint = Color(0xFFFFA202).copy(alpha = 0.12f),
    cautionPast = Color(0xFF8C6A2C),
    sos = Color(0xFFFF4C28),
    sosText = Color(0xFFFF6A4D),
    sosGround = Color(0xFF3A1710),
    sosGroundPressed = Color(0xFF4A1A10),
    sosTrack = Color(0xFFFF4C28).copy(alpha = 0.35f),
    sosSentGround = Color(0xFF2A0F09),
    sosSentGlow = Color(0xFF4A170C),
    sosSentCheck = Color(0xFFFF9C85),
    safe = Color(0xFF00E074),
    safeText = Color(0xFF00E074),
    safeTint = Color(0xFF00E074).copy(alpha = 0.14f),
    primary = Color.White,
    onPrimary = Ink,
    onAccent = Ink,
    switchOff = Color(0xFF44464E),
    knob = Color.White,
    onSos = Color.White,
    sosSentDetail = Color(0xFFFFDCD4).copy(alpha = 0.72f),
    sosSentQuiet = Color.White.copy(alpha = 0.08f),
)

/**
 * Derived from [NightPalette]: Pleos day surfaces (F7F8FA / EDEEF2 / E0E1E6), ink text at the same 100/84/64 %,
 * fills kept, and accent text darkened until it holds 4.5:1 on its light ground.
 */
val DayPalette = GwigaonPalette(
    window = Color(0xFFF7F8FA),
    sunk = Color.White,
    surface = Color(0xFFEDEEF2),
    surfaceHigh = Color(0xFFE0E1E6),
    litTop = Color.White,
    litBottom = Color(0xFFF1F2F5),
    textPrimary = Ink,
    textSecondary = Ink.copy(alpha = 0.84f),
    textTertiary = Ink.copy(alpha = 0.64f),
    line = Ink.copy(alpha = 0.10f),
    lineFaint = Ink.copy(alpha = 0.07f),
    progress = Color(0xFF6A57FF),
    caution = Color(0xFFFFA202),
    cautionText = Color(0xFF8F5400),
    cautionTint = Color(0xFFFFA202).copy(alpha = 0.16f),
    cautionPast = Color(0xFFD9BC85),
    sos = Color(0xFFFE3D16),
    sosText = Color(0xFFB8270A),
    sosGround = Color(0xFFFDE6E0),
    sosGroundPressed = Color(0xFFFBD3C9),
    sosTrack = Color(0xFFFE3D16).copy(alpha = 0.30f),
    sosSentGround = Color(0xFFFDE6E0),
    sosSentGlow = Color(0xFFFBCFC3),
    sosSentCheck = Color(0xFFB8270A),
    safe = Color(0xFF02C265),
    safeText = Color(0xFF006B3C),
    safeTint = Color(0xFF02C265).copy(alpha = 0.16f),
    primary = Ink,
    onPrimary = Color.White,
    onAccent = Ink,
    switchOff = Color(0xFFB4B6BD),
    knob = Color.White,
    onSos = Color.White,
    sosSentDetail = Ink.copy(alpha = 0.72f),
    sosSentQuiet = Ink.copy(alpha = 0.06f),
)

/**
 * The system sans-serif, on purpose (owner, 2026-11-13). Pleos Connect maps it to Asta Sans
 * (`/product/etc/fonts_customization.xml`), so we match the built-in apps, weight mapping included
 * (500 → Regular, 600 → Bold, 700/800 → ExtraBold), and follow the car if it swaps in a brand font.
 * Asta Sans is not bundled.
 */
val GwigaonFont: FontFamily = FontFamily.Default

/** DESIGN.md typography (sp). Driving text ≥ 32; times and distances use tabular figures. */
object GwigaonText {
    private fun style(size: Int, weight: Int, line: Float? = null, tracking: Float = 0f, tnum: Boolean = false) =
        TextStyle(
            fontFamily = GwigaonFont,
            fontSize = size.sp,
            fontWeight = FontWeight(weight),
            lineHeight = line?.let { (size * it).sp } ?: TextStyle.Default.lineHeight,
            letterSpacing = tracking.em,
            fontFeatureSettings = if (tnum) "tnum" else null,
        )

    val status = style(52, 800, line = 1.12f, tracking = -0.01f)
    val numeral = style(112, 700, line = 1f, tracking = -0.03f, tnum = true)
    val headline = style(60, 800, line = 1.1f, tracking = -0.02f)
    val action = style(44, 800, line = 1.1f)
    val title = style(38, 800, line = 1.15f)
    val rowTitle = style(36, 800, line = 1.2f)
    val body = style(34, 600, line = 1.3f)
    /** Running text read parked (the report's AI summary): same size as [body], looser lines for a paragraph. */
    val paragraph = style(34, 500, line = 1.55f)
    val detail = style(32, 600, line = 1.3f)
    /** A past timeline event: quieter by size and weight, never by contrast. */
    val pastTitle = style(32, 600, line = 1.3f)
    val pastTitleOldest = style(32, 500, line = 1.3f)
    /** The SOS bar's turn-signal hint: driving floor (D2 had 28). */
    val sosHint = detail
    val label = style(30, 500, line = 1.3f)
    val labelStrong = style(30, 700, line = 1.3f)
    val time = style(30, 500, tnum = true)
    val timeStrong = style(30, 700, tnum = true)
    val bodyTime = style(34, 600, line = 1.3f, tnum = true)
    val button = style(40, 800)
    val buttonSecondary = style(36, 700)
    val buttonSub = style(28, 600)
    val chip = style(30, 700)
    val pill = style(32, 700)
    val key = style(48, 700, tnum = true)
    /** Parked only (the square SOS at the destination). */
    val small = style(30, 800, tracking = 0.02f)
    /** Parked only: the quiet "how the trip ends" strip on the arrival screens. */
    val note = style(30, 600, line = 1.35f)
    val call = style(32, 800)
    /** Report (parked): a 운행 로그 row's title, and the small grey tags (귀가온 AI, time chips). */
    val logTitle = style(34, 700, line = 1.2f)
    val tag = style(26, 700, tnum = true)
}

/** Pleos radii (dp), pinned by the kit. */
object GwigaonRadius {
    val tile = 22.dp
    val control = 24.dp
    val key = 28.dp
    val card = 32.dp
    val window = 40.dp
    val progress = 7.dp
    /** The report's 귀가온 AI tag and time chips. */
    val tag = 14.dp
}

/** Spacing steps (dp). */
object GwigaonSpace {
    val xs = 8.dp
    val sm = 16.dp
    val md = 24.dp
    val lg = 40.dp
    val xl = 48.dp
    /** Panel padding: 44 top, 40 sides and bottom. */
    val panelTop = 44.dp
    val panelSide = 40.dp
}

/** Component sizes (dp) from DESIGN.md and the prototypes. */
object GwigaonSize {
    val none = 0.dp
    val tile = 72.dp
    val tileIcon = 38.dp
    val pastIcon = 34.dp
    val connector = 4.dp
    val eventGap = 22.dp
    /** The live event's tint band reaches this far past the column on each side. */
    val liveBleed = 22.dp
    val cardPaddingH = 32.dp
    val cardPaddingTop = 34.dp
    val cardPaddingBottom = 30.dp
    val rowPadding = 20.dp
    val sosBar = 150.dp
    val sosBarStart = 30.dp
    val sosBarGap = 28.dp
    val sosRing = 96.dp
    val sosRingCompact = 80.dp
    val sosSquareRing = 104.dp
    val ringStroke = 8.dp
    val progressBar = 14.dp
    val markGap = 3.dp
    val button = 120.dp
    val chip = 72.dp
    val chipPadding = 28.dp
    val chipBorder = 3.dp
    val voicePill = 64.dp
    val voicePillPadding = 22.dp
    val voiceIcon = 34.dp
    val buttonIcon = 42.dp
    val switchPaddingH = 32.dp
    val switchPaddingV = 28.dp
    val switchKnobInset = 8.dp
    val switchTrackWidth = 132.dp
    val switchTrackHeight = 76.dp
    val switchKnob = 60.dp
    val key = 112.dp
    val keyCompact = 88.dp // half-panel keypad under a 설정 step; still above the 76 dp touch minimum
    val keyGap = 18.dp
    val keyIcon = 44.dp
    val pinDot = 44.dp
    val pinDotGap = 28.dp
    val outline = 4.dp
    val destinationRow = 76.dp
    val micDot = 18.dp
    val cueChip = 56.dp
    val cueIcon = 32.dp
    val okIcon = 46.dp
    val sosBeacon = 260.dp
    val sosBeaconInner = 188.dp
    val sosBadge = 132.dp
    val sosBadgeIcon = 72.dp
    // Home, start screen and 설정 (D3c, home.html / start.html / settings.html).
    /** Windows at least this wide get the full-width layouts (Connect-S app area 1682 dp; a half is ~842). */
    val fullFrom = 1200.dp
    val sideColumn = 760.dp
    val door = 220.dp
    val doorFull = 300.dp
    val doorTile = 96.dp
    val doorIcon = 52.dp
    val doorRadius = 40.dp
    val chevron = 40.dp
    /** The account QR (ADR 0019): full in 설정's side panel, compact under the step at half width. */
    val qr = 440.dp
    val qrCompact = 320.dp
    /** White quiet zone around the QR so phone cameras find its edges on a dark panel. */
    val qrQuiet = 24.dp
    val back = 64.dp
    val backIcon = 34.dp
    val stepBar = 8.dp
    val infoRow = 112.dp
    val tag = 44.dp
    val radio = 44.dp
    val radioDot = 18.dp
    val routeDot = 24.dp
    val routeLine = 36.dp
    val spinner = 34.dp
    val splitHandle = 12.dp
    val splitDiagram = 300.dp
    val sosSentButton = 100.dp
    val sosSentIcon = 40.dp
    val ringLine = 3.dp
    // Arrival D4–D7 (arrival.html).
    val call119 = 76.dp
    val call119Icon = 34.dp
    val howIcon = 44.dp
    val pinClose = 76.dp
    val pinCloseIcon = 38.dp
    val divider = 2.dp
    val hairline = 1.dp
    // Report D8 (report.html, ADR 0017).
    /** Smallest touch target in the car; a smaller chip sits centred in it. */
    val touchMin = 76.dp
    val retry = 96.dp
    val skeleton = 30.dp
    val logTime = 104.dp
    val logMarkColumn = 40.dp
    val logMark = 24.dp
    val logMarkTop = 8.dp
    val logRowPadding = 14.dp
}

internal val LocalPalette = staticCompositionLocalOf { NightPalette }

/** The palette for the current day/night mode (set by [GwigaonTheme]). */
val Gwigaon.palette: GwigaonPalette
    @Composable @ReadOnlyComposable
    get() = LocalPalette.current

/** WCAG 2 contrast of [fg] (alpha composited over [bg]) against an opaque [bg]. */
fun contrastRatio(fg: Color, bg: Color): Double {
    val a = luminance(composite(fg, bg))
    val b = luminance(bg)
    return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
}

/** [fg] drawn over an opaque [bg]. */
fun composite(fg: Color, bg: Color): Color {
    val a = fg.alpha
    return Color(
        red = fg.red * a + bg.red * (1 - a),
        green = fg.green * a + bg.green * (1 - a),
        blue = fg.blue * a + bg.blue * (1 - a),
    )
}

private fun luminance(c: Color): Double {
    fun lin(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
}
