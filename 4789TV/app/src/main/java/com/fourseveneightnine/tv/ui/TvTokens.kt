package com.fourseveneightnine.tv.ui

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
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
        val Black = androidx.compose.ui.graphics.Color(0xFF0B0B0B)
        val Slate = androidx.compose.ui.graphics.Color(0xFF141414)
        val Elevated = androidx.compose.ui.graphics.Color(0xFF141414)
        val ElevatedFocused = androidx.compose.ui.graphics.Color(0xFF202020)
        val Text = androidx.compose.ui.graphics.Color(0xFFF7F8FA)
        val Secondary = androidx.compose.ui.graphics.Color(0xFFD0D7E0)
        val Tertiary = androidx.compose.ui.graphics.Color(0xFF8E99A6)
        val BrandOrange = androidx.compose.ui.graphics.Color(0xFFFF7849)
        val BrandGreen = androidx.compose.ui.graphics.Color(0xFF84FF5C)
        val OnAccent = androidx.compose.ui.graphics.Color(0xFF1A0F08)
        val TvCyan = androidx.compose.ui.graphics.Color(0xFF22D3EE)
        // Navigation focus is neutral. Brand color marks actions/choices, not the remote target.
        val Focus = Text
        val Selection = BrandOrange
        val Positive = BrandGreen
        val Warning = androidx.compose.ui.graphics.Color(0xFFFBBF24)
        val Error = androidx.compose.ui.graphics.Color(0xFFFF6B6B)
        val Violet = androidx.compose.ui.graphics.Color(0xFFA874FF)
    }

    object Type {
        // Inter is the app chrome. The content face is the official TV cut of Google Sans Flex.
        val Display = FontFamily(Font(R.font.inter_bold, FontWeight.Bold))
        val UI = FontFamily(
            Font(R.font.inter_regular, FontWeight.Normal),
            Font(R.font.inter_medium, FontWeight.Medium),
            Font(R.font.inter_semibold, FontWeight.SemiBold),
            Font(R.font.inter_bold, FontWeight.Bold),
        )
        @OptIn(ExperimentalTextApi::class)
        val Content = FontFamily(
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
                Font(
                    R.font.google_sans_flex_tv,
                    weight = weight,
                    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
                )
            },
        )
        val Data = FontFamily(Font(R.font.space_mono_regular, FontWeight.Normal), Font(R.font.space_mono_bold, FontWeight.Bold))
        fun display(context: Context): Typeface = font(context, R.font.inter_bold)
        fun ui(context: Context): Typeface = font(context, R.font.inter_regular)
        fun uiMedium(context: Context): Typeface = font(context, R.font.inter_semibold)
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
        const val CatalogPosterColumns = 4
        const val CatalogPosterWidthDp = 376
        const val CatalogPosterHeightDp = CatalogPosterWidthDp * 3 / 2
        const val CatalogPosterGapDp = 24
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
        // Panels acknowledge navigation immediately rather than accelerating from a slow start.
        val Out = Std
        val Pop = CubicBezierEasing(0.34f, 1.46f, 0.44f, 1f)
        const val FocusScaleMillis = 160
        const val FocusRingMillis = 320
        const val NavHideMillis = 180
        /**
         * How long focus must rest before the expensive followers react: the hero swap, the
         * artwork fetch and the ratings warm.
         *
         * This was 0, which disabled the coalescing loops that were written to use it — every
         * repeat of a held D-pad fired all three. Measured on an onn 4K Pro, moving across a rail
         * ran 86% janky frames at a 69ms median while the GPU sat at 11ms, so the cost was UI-thread
         * work per keypress, not drawing. The focus ring itself does NOT wait on this; it is
         * driven separately so the remote still feels instant.
         */
        const val HeroSettleMillis = 140L
        /** How long a poster takes to fade in once decoded, instead of popping. */
        const val ArtworkFadeMillis = 190
        const val RouteEnterMillis = 220
        const val RouteExitMillis = 120
        const val ContentMillis = 160
        /** One sweep of the placeholder shimmer. */
        const val ShimmerSweepMillis = 1_600
        const val ControlsAutoHideMillis = 5_200L
        const val SeekPreviewDismissMillis = 1_400L
        const val BufferingEnterMillis = 180L
        const val MoreRailMillis = 260L
    }
}
