package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.res.ResourcesCompat
import com.fourseveneightnine.tv.R

/** Shared visual tokens for the Compose library and programmatic player chrome. */
internal object TvTokens {
    fun designScale(widthPixels: Int, heightPixels: Int): Float = minOf(widthPixels / 1920f, heightPixels / 1080f).coerceAtLeast(0.01f)
    fun designScale(context: Context): Float = designScale(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
    fun designUnit(context: Context, value: Int): Int = (value * designScale(context)).toInt().coerceAtLeast(1)
    fun designTextPx(context: Context, value: Float): Float = value * designScale(context)

    object Color {
        val Black = androidx.compose.ui.graphics.Color(0xFF090D12)
        val Slate = androidx.compose.ui.graphics.Color(0xFF111A22)
        val Elevated = androidx.compose.ui.graphics.Color(0xFF17202C)
        val ElevatedFocused = androidx.compose.ui.graphics.Color(0xFF22313D)
        val Text = androidx.compose.ui.graphics.Color(0xFFF6F7FB)
        val Secondary = androidx.compose.ui.graphics.Color(0xFFA8B1BF)
        val Tertiary = androidx.compose.ui.graphics.Color(0xFF768294)
        val BrandOrange = androidx.compose.ui.graphics.Color(0xFFFF7849)
        val BrandGreen = androidx.compose.ui.graphics.Color(0xFF84FF5C)
        val OnAccent = androidx.compose.ui.graphics.Color(0xFF1A0F08)
        val TvCyan = androidx.compose.ui.graphics.Color(0xFF22D3EE)
        val Warning = androidx.compose.ui.graphics.Color(0xFFFBBF24)
        val Error = androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        val Violet = androidx.compose.ui.graphics.Color(0xFFA874FF)
    }

    object Type {
        val Display = FontFamily(Font(R.font.bricolage_grotesque_bold, FontWeight.Bold), Font(R.font.bricolage_grotesque_extrabold, FontWeight.ExtraBold))
        val UI = FontFamily(Font(R.font.figtree_regular, FontWeight.Normal), Font(R.font.figtree_medium, FontWeight.Medium), Font(R.font.figtree_semibold, FontWeight.SemiBold), Font(R.font.figtree_bold, FontWeight.Bold))
        val Data = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))
        fun display(context: Context): Typeface = font(context, R.font.bricolage_grotesque_extrabold)
        fun ui(context: Context): Typeface = font(context, R.font.figtree_regular)
        fun uiMedium(context: Context): Typeface = font(context, R.font.figtree_semibold)
        fun data(context: Context): Typeface = font(context, R.font.space_mono_regular)
        private fun font(context: Context, @FontRes id: Int): Typeface = ResourcesCompat.getFont(context, id) ?: Typeface.DEFAULT
    }

    object Geometry {
        // The design canvas is 1920x1080 physical pixels. These insets are therefore the exact
        // five-percent TV-safe boundary at 1080p and scale proportionally at 720p/4K.
        const val ScreenPaddingDp = 96
        const val ScreenVerticalPaddingDp = 54
        const val NavigationTopPaddingDp = 36
        const val NavHeightDp = 136
        const val HeroHeightDp = 440
        const val PosterCardWidthDp = 236
        const val PosterCardHeightDp = 354
        const val WideCardWidthDp = 300
        const val WideCardHeightDp = 169
        const val DetailPosterWidthDp = 480
        const val DetailPosterHeightDp = 720
        const val DetailSourceGridHeightDp = 270
        const val PrimaryActionHeightDp = 60
        const val CompactActionHeightDp = 48
        const val FocusRingDp = 4
        const val RailGapDp = 20
        const val MoreRailWidthDp = 520
        const val PageGlideOffsetDp = 0
    }

    object Motion {
        val Emph = CubicBezierEasing(0.2f, 0.9f, 0.1f, 1f)
        val Std = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        val Out = CubicBezierEasing(0.4f, 0f, 1f, 1f)
        val Pop = CubicBezierEasing(0.34f, 1.46f, 0.44f, 1f)
        const val FocusScaleMillis = 160
        const val FocusRingMillis = 320
        const val NavHideMillis = 180
        const val HeroSettleMillis = 0L
        const val ControlsAutoHideMillis = 5_200L
        const val SeekPreviewDismissMillis = 1_400L
        const val BufferingEnterMillis = 180L
        const val MoreRailMillis = 260L
    }

    fun accentFor(destination: TVLibraryDestination): androidx.compose.ui.graphics.Color = when (destination) {
        TVLibraryDestination.Continue -> Color.BrandOrange
        TVLibraryDestination.TamilMV -> Color.BrandGreen
        TVLibraryDestination.LetterboxdLists -> Color.TvCyan
        TVLibraryDestination.NewFromFriends -> Color.Violet
        TVLibraryDestination.TMDBCatalogs -> Color.Warning
        TVLibraryDestination.Jobs -> Color.Error
    }
}
