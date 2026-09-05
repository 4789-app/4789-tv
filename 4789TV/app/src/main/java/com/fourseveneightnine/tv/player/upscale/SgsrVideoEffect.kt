package com.fourseveneightnine.tv.player.upscale

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import kotlin.math.roundToInt

/**
 * Super-resolves the decoded frame toward the display size with Snapdragon GSR v1
 * (edge-direction variant) inside media3's effects pipeline.
 *
 * media3 hands the effect a plain 2D texture — the OES external-texture dance MediaCodec forces on
 * hand-rolled GL pipelines is already done by [androidx.media3.effect.DefaultVideoFrameProcessor]
 * before any [GlEffect] runs. The effect declares itself a no-op when the content already fills the
 * panel, so 4K-native titles skip the pass entirely rather than paying for a copy.
 */
@UnstableApi
internal class SgsrVideoEffect(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val mode: UpscaleMode,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        SgsrShaderProgram(displayWidth, displayHeight, mode, useHdr)

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean =
        !UpscalePolicy.shouldApply(inputWidth, inputHeight, displayWidth, displayHeight, hdr = false, mode = mode)
}

@UnstableApi
private class SgsrShaderProgram(
    private val displayWidth: Int,
    private val displayHeight: Int,
    private val mode: UpscaleMode,
    useHdr: Boolean,
) : BaseGlShaderProgram(/* useHighPrecisionColorComponents = */ useHdr, /* texturePoolCapacity = */ 1) {

    private val glProgram: GlProgram
    private val sgsrActive: Boolean

    init {
        // Constructed on the GL thread with the context current, so GL_VERSION is answerable here.
        // SGSR needs GLES 3.1 (textureGather) and assumes an SDR 0..1 signal; HDR titles and
        // pre-3.1 drivers fall back to a plain copy instead of failing the whole playback.
        sgsrActive = !useHdr && UpscalePolicy.supportsSgsr(GLES20.glGetString(GLES20.GL_VERSION))
        glProgram = try {
            if (sgsrActive) {
                GlProgram(VERTEX_SHADER_SGSR, FRAGMENT_SHADER_SGSR)
            } else {
                GlProgram(VERTEX_SHADER_COPY, FRAGMENT_SHADER_COPY)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = UpscalePolicy.scaleFor(inputWidth, inputHeight, displayWidth, displayHeight)
        if (!sgsrActive || !UpscalePolicy.shouldApply(inputWidth, inputHeight, displayWidth, displayHeight, hdr = false, mode = mode)) {
            return Size(inputWidth, inputHeight)
        }
        // ViewportInfo carries the INPUT geometry: xy = texel size, zw = size in pixels.
        glProgram.setFloatsUniform(
            "ViewportInfo",
            floatArrayOf(
                1.0f / inputWidth,
                1.0f / inputHeight,
                inputWidth.toFloat(),
                inputHeight.toFloat(),
            ),
        )
        return Size((inputWidth * scale).roundToInt(), (inputHeight * scale).roundToInt())
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("ps0", inputTexId, /* texUnitIndex = */ 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, /* first = */ 0, /* count = */ 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    private companion object {
        // The fragment source is Qualcomm's sgsr1_shader_mobile_edge_direction.frag
        // (github.com/SnapdragonStudios/snapdragon-gsr, BSD-3-Clause), with three mechanical
        // edits for media3: ViewportInfo is a plain vec4 (GlProgram binds uniforms by the exact
        // name GL reports, and arrays report as "ViewportInfo[0]"), the `highp vec2(...)`
        // constructor precision qualifiers are dropped (invalid strict GLSL ES), and
        // UseEdgeDirection is enabled — the variant Moonlight ships on Android for a minimal
        // extra cost. OperationMode 1 samples RGBA and sharpens against green as the luma proxy.

        val VERTEX_SHADER_SGSR = """
            #version 310 es
            in vec4 aFramePosition;
            out highp vec4 in_TEXCOORD0;
            void main() {
              gl_Position = aFramePosition;
              in_TEXCOORD0 = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 0.0);
            }
        """.trimIndent()

        val FRAGMENT_SHADER_SGSR = """
            #version 310 es
            // Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
            // SPDX-License-Identifier: BSD-3-Clause
            precision mediump float;
            precision highp int;

            #define OperationMode 1
            #define UseEdgeDirection
            #define EdgeThreshold 8.0/255.0
            #define EdgeSharpness 2.0

            uniform highp vec4 ViewportInfo;
            uniform mediump sampler2D ps0;

            in highp vec4 in_TEXCOORD0;
            out vec4 out_Target0;

            float fastLanczos2(float x)
            {
                float wA = x-4.0;
                float wB = x*wA-wA;
                wA *= wA;
                return wB*wA;
            }

            vec2 weightY(float dx, float dy, float c, vec3 data)
            {
                float std = data.x;
                vec2 dir = data.yz;

                float edgeDis = ((dx*dir.y)+(dy*dir.x));
                float x = (((dx*dx)+(dy*dy))+((edgeDis*edgeDis)*((clamp(((c*c)*std),0.0,1.0)*0.7)+-1.0)));

                float w = fastLanczos2(x);
                return vec2(w, w * c);
            }

            vec2 edgeDirection(vec4 left, vec4 right)
            {
                vec2 dir;
                float RxLz = (right.x + (-left.z));
                float RwLy = (right.w + (-left.y));
                vec2 delta;
                delta.x = (RxLz + RwLy);
                delta.y = (RxLz + (-RwLy));
                float lengthInv = inversesqrt((delta.x * delta.x+ 3.075740e-05) + (delta.y * delta.y));
                dir.x = (delta.x * lengthInv);
                dir.y = (delta.y * lengthInv);
                return dir;
            }

            void main()
            {
                vec4 color;
                if(OperationMode == 1)
                    color.xyz = textureLod(ps0,in_TEXCOORD0.xy,0.0).xyz;
                else
                    color.xyzw = textureLod(ps0,in_TEXCOORD0.xy,0.0).xyzw;

                if ( OperationMode!=4)
                {
                    highp vec2 imgCoord = ((in_TEXCOORD0.xy*ViewportInfo.zw)+vec2(-0.5,0.5));
                    highp vec2 imgCoordPixel = floor(imgCoord);
                    highp vec2 coord = (imgCoordPixel*ViewportInfo.xy);
                    vec2 pl = (imgCoord+(-imgCoordPixel));
                    vec4  left = textureGather(ps0,coord, OperationMode);

                    float edgeVote = abs(left.z - left.y) + abs(color[OperationMode] - left.y)  + abs(color[OperationMode] - left.z) ;
                    if(edgeVote > EdgeThreshold)
                    {
                        coord.x += ViewportInfo.x;

                        vec4 right = textureGather(ps0,coord + vec2(ViewportInfo.x, 0.0), OperationMode);
                        vec4 upDown;
                        upDown.xy = textureGather(ps0,coord + vec2(0.0, -ViewportInfo.y),OperationMode).wz;
                        upDown.zw  = textureGather(ps0,coord+ vec2(0.0, ViewportInfo.y), OperationMode).yx;

                        float mean = (left.y+left.z+right.x+right.w)*0.25;
                        left = left - vec4(mean);
                        right = right - vec4(mean);
                        upDown = upDown - vec4(mean);
                        color.w =color[OperationMode] - mean;

                        float sum = (((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))+(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w)))+(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));
                        float sumMean = 1.014185e+01/sum;
                        float std = (sumMean*sumMean);

                        vec3 data = vec3(std, edgeDirection(left, right));

                        vec2 aWY = weightY(pl.x, pl.y+1.0, upDown.x,data);
                        aWY += weightY(pl.x-1.0, pl.y+1.0, upDown.y,data);
                        aWY += weightY(pl.x-1.0, pl.y-2.0, upDown.z,data);
                        aWY += weightY(pl.x, pl.y-2.0, upDown.w,data);
                        aWY += weightY(pl.x+1.0, pl.y-1.0, left.x,data);
                        aWY += weightY(pl.x, pl.y-1.0, left.y,data);
                        aWY += weightY(pl.x, pl.y, left.z,data);
                        aWY += weightY(pl.x+1.0, pl.y, left.w,data);
                        aWY += weightY(pl.x-1.0, pl.y-1.0, right.x,data);
                        aWY += weightY(pl.x-2.0, pl.y-1.0, right.y,data);
                        aWY += weightY(pl.x-2.0, pl.y, right.z,data);
                        aWY += weightY(pl.x-1.0, pl.y, right.w,data);

                        float finalY = aWY.y/aWY.x;
                        float maxY = max(max(left.y,left.z),max(right.x,right.w));
                        float minY = min(min(left.y,left.z),min(right.x,right.w));
                        float deltaY = clamp(EdgeSharpness*finalY, minY, maxY) -color.w;

                        //smooth high contrast input
                        deltaY = clamp(deltaY, -23.0 / 255.0, 23.0 / 255.0);

                        color.x = clamp((color.x+deltaY),0.0,1.0);
                        color.y = clamp((color.y+deltaY),0.0,1.0);
                        color.z = clamp((color.z+deltaY),0.0,1.0);
                    }
                }

                color.w = 1.0;  //assume alpha channel is not used
                out_Target0.xyzw = color;
            }
        """.trimIndent()

        val VERTEX_SHADER_COPY = """
            #version 300 es
            in vec4 aFramePosition;
            out highp vec4 in_TEXCOORD0;
            void main() {
              gl_Position = aFramePosition;
              in_TEXCOORD0 = vec4(aFramePosition.xy * 0.5 + 0.5, 0.0, 0.0);
            }
        """.trimIndent()

        val FRAGMENT_SHADER_COPY = """
            #version 300 es
            precision mediump float;
            uniform mediump sampler2D ps0;
            in highp vec4 in_TEXCOORD0;
            out vec4 out_Target0;
            void main() {
              out_Target0 = vec4(texture(ps0, in_TEXCOORD0.xy).rgb, 1.0);
            }
        """.trimIndent()
    }
}
