package com.fourseveneightnine.tv.client.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fourseveneightnine.tv.client.ui.theme.LocalReduceMotion
import com.fourseveneightnine.tv.client.ui.theme.TvColor
import com.fourseveneightnine.tv.client.ui.theme.TvMotion

/** The decoded landscape and the width at which the screen draws it. */
internal data class TvGlassArtwork(val bitmap: Bitmap, val displayedWidth: Float, val offsetX: Float = 0f)

internal interface TvGlassLens {
    fun brush(size: Size, origin: Offset, displayedWidth: Float, radius: Float, edge: Float, bend: Float,
        frost: Float = 0f, brightnessLimit: Float = 1f): Brush
}

internal fun createTvGlassLens(bitmap: Bitmap?, surface: String): TvGlassLens? {
    if (bitmap == null || Build.VERSION.SDK_INT < 33) return null
    return runCatching {
        ArtworkLens(bitmap).also {
            Trace.beginSection("TvGlassLens:$surface")
            Trace.endSection()
        }
    }.getOrNull()
}

/** Sample the decoded artwork directly and bend it at the edge. */
@RequiresApi(33)
private class ArtworkLens(private val image: Bitmap) : TvGlassLens {
    private val shader = RuntimeShader(
        """
        uniform shader artwork;
        uniform float2 extent;
        uniform float2 offset;
        uniform float pixels;
        uniform float radius;
        uniform float edgeWidth;
        uniform float bend;
        uniform float frost;
        uniform float brightnessLimit;

        half4 main(float2 p) {
            float2 halfSize = extent * 0.5;
            float2 q = abs(p - halfSize) - (halfSize - radius);
            float distance = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
            float rim = 1.0 - smoothstep(0.0, edgeWidth, max(-distance, 0.0));
            float2 toCenter = halfSize - p;
            float2 displacement = toCenter / max(1.0, length(toCenter)) * bend * rim * rim;
            float2 uv = (p + offset + displacement) * pixels;
            half3 sampleColor = artwork.eval(uv).rgb;
            if (frost > 0.0) {
                float2 delta = float2(frost * pixels, frost * pixels);
                sampleColor = sampleColor * 0.28 +
                    (artwork.eval(uv + delta).rgb + artwork.eval(uv - delta).rgb +
                     artwork.eval(uv + float2(delta.x, -delta.y)).rgb +
                     artwork.eval(uv + float2(-delta.x, delta.y)).rgb) * 0.18;
            }
            if (brightnessLimit < 1.0) {
                float brightness = dot(float3(sampleColor), float3(0.2126, 0.7152, 0.0722));
                sampleColor *= brightnessLimit / max(brightness, brightnessLimit);
            }
            return half4(sampleColor, 1.0);
        }
        """.trimIndent(),
    ).apply {
        setInputShader("artwork", BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }
    private val cachedBrush = ShaderBrush(shader)

    override fun brush(size: Size, origin: Offset, displayedWidth: Float, radius: Float, edge: Float, bend: Float,
        frost: Float, brightnessLimit: Float): Brush {
        shader.setFloatUniform("extent", size.width, size.height)
        shader.setFloatUniform("offset", origin.x, origin.y)
        shader.setFloatUniform("pixels", image.width / displayedWidth.coerceAtLeast(1f))
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("edgeWidth", edge.coerceAtLeast(1f))
        shader.setFloatUniform("bend", bend)
        shader.setFloatUniform("frost", frost)
        shader.setFloatUniform("brightnessLimit", brightnessLimit)
        return cachedBrush
    }
}

private class GlassPosition { var coordinates: LayoutCoordinates? = null }

@Composable
internal fun Modifier.tvGlassPill(
    artwork: () -> TvGlassArtwork?,
    focused: Boolean,
    selected: Boolean,
): Modifier {
    val position = remember { GlassPosition() }
    val art = artwork()
    // The lens belongs to the decoded image. Rapid focus changes reuse it.
    val lens = remember(art?.bitmap) { lazy(LazyThreadSafetyMode.NONE) { createTvGlassLens(art?.bitmap, "Pill") } }
    val reduceMotion = LocalReduceMotion.current
    val lift = animateFloatAsState(
        targetValue = if (focused) 1f else 0f,
        animationSpec = tween(if (reduceMotion) 0 else 150, easing = TvMotion.Out),
        label = "glassPillFocus",
    )
    return onGloballyPositioned { position.coordinates = it }.drawWithCache {
        val tint = Brush.verticalGradient(
            0f to TvColor.Elevated2.copy(alpha = 0.84f),
            1f to TvColor.Canvas.copy(alpha = 0.92f),
        )
        val rim = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.19f),
            0.55f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.10f),
        )
        val highlight = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.38f),
            0.62f to Color.Transparent,
            1f to Color.White.copy(alpha = 0.12f),
        )
        val glintWidth = size.width * 0.65f
        val glint = Brush.horizontalGradient(
            0f to Color.Transparent,
            0.5f to Color.White.copy(alpha = 0.07f),
            1f to Color.Transparent,
            endX = glintWidth,
        )
        val radius = size.height / 2f
        val edge = 12.dp.toPx()
        val bend = 15.dp.toPx()
        val line = Stroke(1.5.dp.toPx())
        onDrawWithContent {
            val amount = lift.value
            if (amount > 0f && art != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                val offset = position.coordinates?.takeIf { it.isAttached }?.positionInRoot()
                if (offset != null) lens.value?.let {
                    drawRect(it.brush(size, offset.copy(x = offset.x - art.offsetX), art.displayedWidth, radius, edge, bend * amount), alpha = amount)
                }
            }
            drawRect(tint)
            if (selected) drawRect(TvColor.Selection.copy(alpha = 0.14f))
            drawRoundRect(rim, cornerRadius = CornerRadius(radius), style = line)
            if (amount > 0f) {
                val glintLeft = -glintWidth * 0.5f + size.width * 0.64f * amount
                translate(left = glintLeft) {
                    drawRect(glint, size = Size(glintWidth, size.height), alpha = amount)
                }
                drawRoundRect(highlight, cornerRadius = CornerRadius(radius), style = line, alpha = amount)
            }
            drawContent()
        }
    }
}

@Composable
internal fun Modifier.tvGlassPosterRim(artwork: State<Bitmap?>, focused: Boolean): Modifier {
    val hasFocus = rememberUpdatedState(focused)
    val rimModifier = remember(artwork) {
        Modifier.drawWithCache {
            val image = artwork.value?.takeIf {
                val aspect = it.width.toFloat() / it.height
                aspect in 0.62f..0.71f
            }
            val inset = 4.dp.toPx()
            val radius = 13.dp.toPx()
            val edge = 12.dp.toPx()
            val bend = 10.dp.toPx()
            val stroke = Stroke(6.dp.toPx())
            val line = Stroke(1.5.dp.toPx())
            val area = Size((size.width - 2f * inset).coerceAtLeast(0f), (size.height - 2f * inset).coerceAtLeast(0f))
            val topLeft = Offset(inset, inset)
            val corners = CornerRadius(radius)
            val rim = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.42f), 0.62f to Color.Transparent,
                1f to Color.White.copy(alpha = 0.13f))
            // Build the shader only if this poster gains focus. Focus moves do not invalidate it.
            val lensBrush by lazy(LazyThreadSafetyMode.NONE) {
                createTvGlassLens(image, "Poster")?.brush(size, Offset.Zero, size.width, radius, edge, bend)
            }
            onDrawWithContent {
                drawContent()
                if (hasFocus.value) {
                    if (drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                        lensBrush?.let {
                            drawRoundRect(it, topLeft = topLeft, size = area, cornerRadius = corners,
                                style = stroke, alpha = 0.68f)
                        }
                    }
                    drawRoundRect(rim, topLeft = topLeft, size = area, cornerRadius = corners, style = line)
                }
            }
        }
    }
    return then(rimModifier)
}

/** Immediate guarded focus; the small reflection can settle without covering the artwork. */
@Composable
internal fun GlassFocusUnderline(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    val glint = remember { Animatable(if (reduceMotion) 1f else 0f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) glint.snapTo(1f) else glint.animateTo(1f, tween(150, easing = TvMotion.Out))
    }
    Box(modifier.width(40.dp).height(10.dp).drawWithCache {
        val light = Brush.horizontalGradient(
            listOf(Color.Transparent, TvColor.Focus.copy(alpha = 0.52f), Color.Transparent),
        )
        onDrawBehind {
            val center = size.height / 2f
            val radius = CornerRadius(3.dp.toPx())
            drawRoundRect(TvColor.Canvas, topLeft = Offset(0f, center - 3.dp.toPx()),
                size = Size(size.width, 6.dp.toPx()), cornerRadius = radius)
            drawRoundRect(light, topLeft = Offset(0f, center - 4.dp.toPx()),
                size = Size(size.width, 8.dp.toPx()), cornerRadius = radius, alpha = glint.value)
            drawRoundRect(TvColor.Focus, topLeft = Offset(2.dp.toPx(), center - 1.5.dp.toPx()),
                size = Size(size.width - 4.dp.toPx(), 3.dp.toPx()), cornerRadius = radius)
        }
    })
}

/** Frost the part of the title backdrop covered by Sources. Foreground controls stay sharp. */
@Composable
internal fun Modifier.tvSourcesGlass(artwork: TvGlassArtwork?, panelProgress: State<Float>): Modifier {
    val position = remember { GlassPosition() }
    val lens = remember(artwork?.bitmap) { createTvGlassLens(artwork?.bitmap, "Sources") }
    return onGloballyPositioned { position.coordinates = it }.drawWithCache {
        val tint = Brush.verticalGradient(listOf(
            TvColor.Canvas.copy(alpha = 0.54f),
            TvColor.Elevated.copy(alpha = 0.66f),
        ))
        val radius = 28.dp.toPx()
        val frost = 22.dp.toPx()
        onDrawWithContent {
            if (artwork != null && lens != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                // Refresh the sample as the panel's layer slides across the artwork.
                val artworkAlpha = 0.90f * panelProgress.value
                val origin = position.coordinates?.takeIf { it.isAttached }?.positionInRoot()
                if (origin != null) {
                    val artHeight = artwork.displayedWidth * artwork.bitmap.height / artwork.bitmap.width
                    clipRect(bottom = (artHeight - origin.y).coerceIn(0f, size.height)) {
                        drawRect(
                            lens.brush(size, origin.copy(x = origin.x - artwork.offsetX), artwork.displayedWidth,
                                radius, edge = radius, bend = 0f, frost = frost, brightnessLimit = 0.62f),
                            alpha = artworkAlpha,
                        )
                    }
                }
            }
            drawRect(tint)
            drawContent()
        }
    }
}

/** Smoked glass over decoded artwork, or a high-contrast tint over UI and video. */
@Composable
internal fun Modifier.tvGlassPanel(
    radius: Dp,
    artwork: Bitmap? = null,
    backdrop: TvGlassArtwork? = null,
    opaqueBase: Boolean = false,
): Modifier {
    val image = backdrop?.bitmap ?: artwork
    val lens = remember(image) { createTvGlassLens(image, "Panel") }
    val position = remember { GlassPosition() }
    return then(if (backdrop != null && lens != null) {
        Modifier.onGloballyPositioned { position.coordinates = it }
    } else Modifier).clip(RoundedCornerShape(radius)).drawWithCache {
        val corner = CornerRadius(radius.toPx())
        val smoke = Brush.linearGradient(
            listOf(
                TvColor.Elevated2.copy(alpha = 0.93f),
                TvColor.Elevated.copy(alpha = 0.94f),
                TvColor.Canvas.copy(alpha = 0.97f),
            ),
            start = Offset.Zero, end = Offset(size.width * 0.75f, size.height),
        )
        val light = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.04f), Color.Transparent),
            center = Offset(size.width * 0.19f, -size.height * 0.2f), radius = (size.width * 0.73f).coerceAtLeast(1f))
        val rim = Brush.linearGradient(0f to Color.White.copy(alpha = 0.37f),
            0.33f to Color.White.copy(alpha = 0.18f), 0.72f to Color.White.copy(alpha = 0.07f),
            1f to Color.White.copy(alpha = 0.19f))
        val stroke = Stroke(2.dp.toPx())
        onDrawWithContent {
            if (opaqueBase) drawRoundRect(TvColor.Canvas, cornerRadius = corner)
            if (lens != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
                if (backdrop != null) {
                    position.coordinates?.takeIf { it.isAttached }?.positionInRoot()?.let { origin ->
                        val offset = origin.copy(x = origin.x - backdrop.offsetX)
                        drawRoundRect(lens.brush(size, offset, backdrop.displayedWidth, corner.x,
                            11.dp.toPx(), 9.dp.toPx(), frost = 24.dp.toPx()), cornerRadius = corner)
                    }
                } else if (artwork != null) {
                    val scale = maxOf(size.width / artwork.width, size.height / artwork.height)
                    val offset = Offset((artwork.width * scale - size.width) / 2f, (artwork.height * scale - size.height) / 2f)
                    drawRoundRect(lens.brush(size, offset, artwork.width * scale, corner.x,
                        11.dp.toPx(), 9.dp.toPx(), frost = 14.dp.toPx()), cornerRadius = corner, alpha = 0.72f)
                }
            }
            drawRoundRect(smoke, cornerRadius = corner)
            drawRoundRect(light, cornerRadius = corner)
            drawContent()
            drawRoundRect(rim, cornerRadius = corner, style = stroke)
        }
    }
}
