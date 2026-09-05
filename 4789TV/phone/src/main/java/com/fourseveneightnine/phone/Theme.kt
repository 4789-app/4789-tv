package com.fourseveneightnine.phone

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The phone app's look, taken from the iOS app rather than invented here.
 *
 * Every colour below is copied from `App/FourSevenEightNine/Shared/Theme.swift` and every radius
 * and spacing step from `App/FourSevenEightNine/Shared/DesignTokens.swift`. The hex comment on
 * each line is the one written beside the value in the Swift source, so the two can be diffed by
 * eye. Do not tune a value here alone — change it on iOS first, then mirror it.
 *
 * The canvas is the SLATE one, which is the iOS default: a cool grey rather than a near-black.
 * iOS also ships a light canvas, so a light scheme is provided for phones set to light mode using
 * that canvas's own values.
 */
object Brand {

    // MARK: Canvas — Theme.swift `background` / `elevated` / `elevated2`, slate canvas
    val background = Color(0xFF14181C)  // #14181c (Letterboxd grey)
    val elevated = Color(0xFF20262E)    // #20262e (lifted slate card)
    val elevated2 = Color(0xFF2C3440)   // #2c3440 (Letterboxd card)

    // MARK: Text — Theme.swift `textPrimary` / `textSecondary` / `textMuted`, slate canvas
    val textPrimary = Color(0xFFF4F5F8)   // #f4f5f8
    val textSecondary = Color(0xFF9FB0C0) // #9fb0c0 (cool blue-grey dek)
    val textMuted = Color(0xFF6B7A8A)     // #6b7a8a (cool grey)

    // MARK: Brand — Theme.swift `rawBrandOrange` / `rawBrandGreen`
    val orange = Color(0xFFFF7849) // #ff7849
    val green = Color(0xFF84FF5C)  // #84ff5c

    /** Theme.swift `onAccent`: the ink that sits on top of the orange. */
    val onAccent = Color(0xFF1A0F08) // #1a0f08

    // MARK: State — Theme.swift `warmAmber` / `softCoral`
    val warning = Color(0xFFFBBF24) // #fbbf24
    val error = Color(0xFFFF6B6B)   // #ff6b6b

    // MARK: Tones — Theme.swift `cyanTone` / `violetTone`
    val tvTone = Color(0xFF22D3EE)       // #22d3ee
    val downloadTone = Color(0xFFA874FF) // #a874ff (violet)

    // MARK: Light canvas — Theme.swift `canvas.isLight` branches
    val lightBackground = Color(0xFFFFFFFF)
    val lightElevated = Color(0xFFF3F4F6)   // #f3f4f6
    val lightElevated2 = Color(0xFFE9EBEF)  // #e9ebef
    val lightTextPrimary = Color(0xFF0B0D10)   // #0b0d10
    val lightTextSecondary = Color(0xFF475569) // #475569 slate-600
    val lightTextMuted = Color(0xFF64748B)     // #64748b slate-500
}

/**
 * DesignTokens.Radius. The names are the iOS surface CLASSES, not pixel values, so the intent
 * survives a re-tune.
 */
object Radius {
    val chip = 8.dp
    val tile = 9.dp
    val card = 12.dp
    val cardProminent = 16.dp
    val sheet = 20.dp
    val sheetProminent = 22.dp
    val control = 10.dp
}

/** DesignTokens.Spacing — the 4pt grid the iOS app already follows. */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
}

/** Inter is the iOS app's face. The same TTF files ship here, from the same folder. */
private val Inter = FontFamily(
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private val BrandTypography = Typography().run {
    val base = this
    Typography(
        displayLarge = base.displayLarge.brand(40.sp, FontWeight.Bold),
        displayMedium = base.displayMedium.brand(32.sp, FontWeight.Bold),
        displaySmall = base.displaySmall.brand(28.sp, FontWeight.Bold),
        headlineLarge = base.headlineLarge.brand(26.sp, FontWeight.Bold),
        headlineMedium = base.headlineMedium.brand(22.sp, FontWeight.Bold),
        headlineSmall = base.headlineSmall.brand(19.sp, FontWeight.SemiBold),
        titleLarge = base.titleLarge.brand(18.sp, FontWeight.SemiBold),
        titleMedium = base.titleMedium.brand(16.sp, FontWeight.SemiBold),
        titleSmall = base.titleSmall.brand(14.sp, FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.brand(16.sp, FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.brand(14.sp, FontWeight.SemiBold),
        bodySmall = base.bodySmall.brand(12.sp, FontWeight.SemiBold),
        labelLarge = base.labelLarge.brand(14.sp, FontWeight.SemiBold),
        labelMedium = base.labelMedium.brand(12.sp, FontWeight.SemiBold),
        labelSmall = base.labelSmall.brand(11.sp, FontWeight.SemiBold),
    )
}

/**
 * Inter ships here in SemiBold and Bold only, so there is no Regular to fall back to. Asking for
 * [FontWeight.Normal] would make Android synthesise one by thinning the SemiBold, which reads as a
 * blurry weight rather than a lighter one. Every style therefore names a weight that exists.
 */
private fun TextStyle.brand(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight) =
    copy(fontFamily = Inter, fontSize = size, fontWeight = weight, lineHeight = size * 1.3f)

private val BrandShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.chip),
    small = RoundedCornerShape(Radius.control),
    medium = RoundedCornerShape(Radius.card),
    large = RoundedCornerShape(Radius.cardProminent),
    extraLarge = RoundedCornerShape(Radius.sheet),
)

private val DarkColors = darkColorScheme(
    primary = Brand.orange,
    onPrimary = Brand.onAccent,
    primaryContainer = Brand.elevated2,
    onPrimaryContainer = Brand.textPrimary,
    secondary = Brand.tvTone,
    onSecondary = Brand.onAccent,
    // Selected filter chips read from the secondary container. Left at the Material default it
    // stays a pale lavender, which is the stock look this theme exists to remove.
    secondaryContainer = Brand.elevated2,
    onSecondaryContainer = Brand.textPrimary,
    tertiary = Brand.downloadTone,
    onTertiary = Brand.onAccent,
    background = Brand.background,
    onBackground = Brand.textPrimary,
    surface = Brand.background,
    onSurface = Brand.textPrimary,
    surfaceVariant = Brand.elevated,
    onSurfaceVariant = Brand.textSecondary,
    surfaceContainer = Brand.elevated,
    surfaceContainerHigh = Brand.elevated2,
    outline = Brand.textMuted,
    outlineVariant = Brand.elevated2,
    error = Brand.error,
    onError = Brand.onAccent,
)

private val LightColors = lightColorScheme(
    primary = Brand.orange,
    onPrimary = Brand.onAccent,
    primaryContainer = Brand.lightElevated2,
    onPrimaryContainer = Brand.lightTextPrimary,
    secondary = Brand.tvTone,
    onSecondary = Brand.onAccent,
    secondaryContainer = Brand.lightElevated2,
    onSecondaryContainer = Brand.lightTextPrimary,
    tertiary = Brand.downloadTone,
    onTertiary = Brand.onAccent,
    background = Brand.lightBackground,
    onBackground = Brand.lightTextPrimary,
    surface = Brand.lightBackground,
    onSurface = Brand.lightTextPrimary,
    surfaceVariant = Brand.lightElevated,
    onSurfaceVariant = Brand.lightTextSecondary,
    surfaceContainer = Brand.lightElevated,
    surfaceContainerHigh = Brand.lightElevated2,
    outline = Brand.lightTextMuted,
    outlineVariant = Brand.lightElevated2,
    error = Brand.error,
    onError = Brand.lightBackground,
)

/**
 * Wraps the whole phone app.
 *
 * Android's "dynamic colour" is deliberately NOT used. It repaints an app from the phone's
 * wallpaper, which would throw away the palette above and is why this app used to look like a
 * stock Material sample.
 */
/**
 * The slate canvas is the iOS default, so it is the default here too, whatever the phone's own
 * light/dark setting says. iOS treats the canvas as an app choice rather than a system one: the
 * artwork, the poster grid and the text colours were all tuned against slate. Following the system
 * instead would hand half the users a canvas the app was never designed on.
 *
 * The light scheme stays built and reachable through [dark], ready for a canvas setting to select
 * it once this app grows one, matching the iOS light canvas.
 */
private const val DEFAULT_TO_SLATE_CANVAS = true

@Composable
fun FourSevenEightNineTheme(
    dark: Boolean = if (DEFAULT_TO_SLATE_CANVAS) true else isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = BrandTypography,
        shapes = BrandShapes,
        content = content,
    )
}
