package com.fourseveneightnine.tv.player

import androidx.media3.common.PlaybackException

/**
 * Pure classifier from a Media3 failure to the wire category carried by X4789.OnPlaybackError.
 * A decoder-init mime beats the error code: "no DTS decoder" is a codec story even when the
 * surrounding exception is generic. Unknown codes stay "unknown" — the phone treats that as
 * "playback failed on the TV", never as a connection loss.
 */
object PlaybackErrorTaxonomy {
    const val CODEC_AUDIO = "codec-audio"
    const val CODEC_VIDEO = "codec-video"
    const val SOURCE = "source"
    const val NETWORK = "network"
    const val UNKNOWN = "unknown"

    fun categorize(errorCode: Int, decoderMime: String?): String = when {
        decoderMime?.startsWith("audio/") == true -> CODEC_AUDIO
        decoderMime?.startsWith("video/") == true -> CODEC_VIDEO
        errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
            errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> NETWORK
        errorCode == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE ||
            errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
            errorCode == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE ||
            errorCode in PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED..
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> SOURCE
        errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> CODEC_VIDEO
        errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
            errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> CODEC_AUDIO
        else -> UNKNOWN
    }
}
