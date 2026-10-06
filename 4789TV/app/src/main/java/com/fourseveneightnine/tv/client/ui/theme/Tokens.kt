package com.fourseveneightnine.tv.client.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.fourseveneightnine.tv.ui.TvTokens

/**
 * The 4789 TV design tokens, from `docs/design/TV_DESIGN_SPEC.md` §0 and §16.
 *
 * Every number below is a pixel on the 1920 x 1080 design canvas. [TvTheme] overrides
 * [LocalDensity] so that one `dp` in this tree *is* one design pixel, which is why the spec's
 * coordinates can be written here literally. On a 720p or 4K panel one scale factor —
 * `min(width / 1920, height / 1080)` — moves the whole layout at once.
 *
 * Motion and geometry constants are NOT duplicated here. They live in
 * [com.fourseveneightnine.tv.ui.TvTokens] because the receiver chrome shares them, and this file
 * references that object rather than restating its numbers.
 */
internal object TvColor {
    val Canvas = Color(0xFF0C0E12)
    val CanvasBlack = Color(0xFF07080A)
    val Elevated = Color(0xFF16191E)
    val Elevated2 = Color(0xFF222830)
    val PosterPlaceholder = Color(0xFF12151A)
    val TextPrimary = Color(0xFFF7F8FA)
    val TextSecondary = Color(0xFFD0D7E0)
    val TextMuted = Color(0xFF8E99A6)
    val Accent = Color(0xFFFF7849)
    val OnAccent = Color(0xFF1A0F08)
    val Cached = Color(0xFF84FF5C)
    val Focus = Color(0xFF22D3EE)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFFF6B6B)
    val Border = Color(0x1AFFFFFF)
    val Scrim = Canvas.copy(alpha = 0.06f)

    /** User folder accents, in order. They appear on folder cards and nowhere else. */
    val CollectionAccents = listOf(
        Color(0xFFE9A23B), Color(0xFFE86A8E), Color(0xFFC264D6), Color(0xFF3FB6A0),
        Color(0xFF9FCF5B), Color(0xFF5BA9CF), Color(0xFFA874FF), Color(0xFFFF4DB8),
    )
}

/** Type roles, spec §0.6. Inter throughout; tabular figures keep changing values steady. */
internal object TvType {
    private val Inter = TvTokens.Type.UI

    val HeroTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 56.sp, lineHeight = 64.sp, letterSpacing = (-0.02).em)
    val ScreenTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 48.sp, letterSpacing = (-0.02).em)
    val PlateTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 40.sp, letterSpacing = (-0.01).em)
    val ShelfHeader = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp)
    val PanelHeader = ShelfHeader
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 34.sp)
    val ControlLabel = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp)
    val CardTitle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    val Meta = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 20.sp, lineHeight = 26.sp)
    val Badge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 22.sp, letterSpacing = 0.06.em)
    val Key = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 28.sp, lineHeight = 34.sp)

    fun data(size: Int) = TextStyle(
        fontFamily = Inter,
        fontWeight = FontWeight.Normal,
        fontSize = size.sp,
        lineHeight = (size * 1.3f).sp,
        fontFeatureSettings = "tnum",
    )
}

/** Radii, spec §0.7. */
internal object TvShape {
    val Chip = RoundedCornerShape(8.dp)
    val Tile = RoundedCornerShape(9.dp)
    val Card = RoundedCornerShape(12.dp)
    val CardProminent = RoundedCornerShape(16.dp)
    val Control = RoundedCornerShape(10.dp)
    val Panel = RoundedCornerShape(20.dp)
    val Badge = RoundedCornerShape(6.dp)
}

/** The four spacing steps. A 36 px gap is a rest zone between rails. */
internal object TvSpace {
    val XS = 8.dp
    val S = 16.dp
    val M = 24.dp
    val L = 36.dp
}

/** Fixed geometry from spec §0.1–§0.4 and §0.7. */
internal object TvGeom {
    val SafeLeft = 96.dp
    val SafeTop = 54.dp
    val SafeRight = 1824.dp
    val SafeBottom = 1026.dp

    val RailStrip = 184.dp
    val RailExpanded = 376.dp
    val RailItem = 88.dp
    val RailItemPitch = 104.dp
    /** 96 + 264 leaves 16 px inside the 376 px panel, so the exterior focus ring is not cut. */
    val RailPanelWidth = 264.dp

    /** The first card sits here; 36 px of rest after the rail strip. */
    val ContentLeft = 220.dp
    /** Room at the start of a row so the first card's focus ring is not clipped. */
    val RowEdgeInset = 16.dp

    /** A 236 px card and a 20 px gap. */
    val PosterWidth = 236.dp
    val PosterHeight = 354.dp
    val PosterPitch = 256.dp
    val PosterCompactWidth = 196.dp
    val PosterCompactHeight = 294.dp
    val WideCardWidth = 300.dp
    val WideCardHeight = 169.dp
    val FolderCardWidth = 380.dp
    val FolderCardHeight = 214.dp

    /** Row pivot: the focused card's left edge pins to poster column 3. */
    val RowPivotX = 732.dp

    val FocusRingWidth = 4.dp
    // Browse focus moves dozens of times in a sitting. The cyan ring is the feedback; scaling
    // every poster created a layer and a 160 ms animation for each D-pad tick on low-end boxes.
    val FocusScale = 1f

    /**
     * Room under a card that scales from its top edge. The restrained 1.04 focus scale keeps the
     * ring distinct without making a dense row reflow visually.
     */
    val FocusLabelGap = 16.dp
    val ButtonFocusScale = 1f
    val ButtonHeight = 60.dp
    val ButtonHeightDense = 56.dp
    val ButtonHeightFirstRun = 64.dp
    val SidePanelWidth = 640.dp
    val SidePanelWideWidth = 720.dp
}

/** Easings and durations. Sourced from `TvTokens.Motion`; never restated. */
internal object TvMotion {
    val Emph: Easing = TvTokens.Motion.Emph
    val Std: Easing = TvTokens.Motion.Std
    val Out: Easing = TvTokens.Motion.Out

    const val FocusScaleMillis = TvTokens.Motion.FocusScaleMillis
    const val FocusRingMillis = TvTokens.Motion.FocusRingMillis
    const val RailMillis = TvTokens.Motion.NavHideMillis
    const val CrossfadeMillis = TvTokens.Motion.ArtworkFadeMillis
    const val ShimmerSweepMillis = TvTokens.Motion.ShimmerSweepMillis
    const val HeroSettleMillis = TvTokens.Motion.HeroSettleMillis
    const val ControlsAutoHideMillis = TvTokens.Motion.ControlsAutoHideMillis
    const val SeekBadgeMillis = TvTokens.Motion.SeekPreviewDismissMillis
    const val BufferingEnterMillis = TvTokens.Motion.BufferingEnterMillis

    /** Rows, columns and side panels all move over this. */
    const val ScrollMillis = 180
    const val PanelSlideMillis = 190
}

/**
 * Reduce Motion, from Settings → Look. Every duration in this tree becomes zero when it is on.
 * Defaulted rather than static so a preview can flip it.
 */
internal val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Scale factor between the design canvas and this panel, for code that needs raw pixels. */
internal val LocalDesignScale = staticCompositionLocalOf { 1f }

/**
 * The panel's real density, kept so a subtree that was written against platform `dp` — the
 * existing `TVSettingsSurface`, which does its own design scaling — can be handed it back.
 */
internal val LocalPlatformDensity = staticCompositionLocalOf<Density> {
    error("LocalPlatformDensity was not provided")
}

@Composable
internal fun TvTheme(
    reduceMotion: Boolean = false,
    blackCanvas: Boolean = false,
    content: @Composable () -> Unit,
) {
    val platformDensity = LocalDensity.current
    val configuration = LocalConfiguration.current
    val scale = remember(configuration.screenWidthDp, configuration.screenHeightDp, platformDensity.density) {
        TvTokens.designScale(
            (configuration.screenWidthDp * platformDensity.density).toInt(),
            (configuration.screenHeightDp * platformDensity.density).toInt(),
        )
    }
    // fontScale is pinned to 1: a television has no reading distance the viewer can change, and
    // the spec's 20 px floor is the accessibility contract instead.
    val designDensity = remember(scale) { Density(scale, 1f) }
    val canvas = if (blackCanvas) TvColor.CanvasBlack else TvColor.Canvas
    val scheme = remember(canvas) {
        darkColorScheme(
            primary = TvColor.Accent,
            onPrimary = TvColor.OnAccent,
            secondary = TvColor.Elevated2,
            onSecondary = TvColor.TextPrimary,
            background = canvas,
            onBackground = TvColor.TextPrimary,
            surface = TvColor.Elevated,
            onSurface = TvColor.TextPrimary,
            surfaceVariant = TvColor.Elevated2,
            onSurfaceVariant = TvColor.TextSecondary,
            error = TvColor.Error,
            onError = TvColor.OnAccent,
            border = TvColor.Border,
        )
    }
    CompositionLocalProvider(
        LocalDensity provides designDensity,
        LocalPlatformDensity provides platformDensity,
        LocalDesignScale provides scale,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = MaterialTheme.typography,
            content = content,
        )
    }
}
