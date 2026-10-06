package com.fourseveneightnine.tv.client.playback

import android.content.Context
import com.fourseveneightnine.tv.player.VideoCodecProbe

/**
 * Which video codecs this box decodes in hardware, in lower case, for [
 * com.fourseveneightnine.tv.client.data.streams.StreamRanker].
 *
 * The ranker demotes a Dolby Vision profile 5 release hard when `dolbyvision` is missing here,
 * because a box without a DV decoder plays it as a green, washed-out picture rather than as plain
 * HDR. So the answer has to be the box's own truth and nothing else.
 *
 * It reads `MediaCodecList` through [VideoCodecProbe] and is NEVER gated on the SDK level. Fire OS
 * 7 reports API 28 and Amazon backported the AV1 constants: an SDK check rejects hardware that in
 * fact decodes AV1 perfectly well. The same probe already answers `X4789.GetReceiverInfo`, so the
 * phone's ranking and the box's ranking read one list.
 *
 * The probe walks every installed codec, which is a slow Binder-ish call on Fire OS, so the answer
 * is read once per process and held.
 */
internal object HardwareCodecs {

    @Volatile
    private var cached: Set<String>? = null

    /** The brief's signature. The probe needs no [Context]; it is taken so call sites read alike. */
    fun names(@Suppress("UNUSED_PARAMETER") context: Context): Set<String> = names()

    fun names(): Set<String> = cached ?: probe().also { cached = it }

    /** Tests and a codec-override screen re-read the box; nothing else calls this. */
    fun invalidate() {
        cached = null
    }

    private fun probe(): Set<String> =
        runCatching { VideoCodecProbe.hardwareDecoded() }
            .getOrDefault(emptyList())
            .map { it.lowercase() }
            .toSet()
}
