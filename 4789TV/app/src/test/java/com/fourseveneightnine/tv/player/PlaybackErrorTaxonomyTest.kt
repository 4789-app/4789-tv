package com.fourseveneightnine.tv.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackErrorTaxonomyTest {
    @Test
    fun decoderMimeBeatsErrorCode() {
        assertEquals(
            PlaybackErrorTaxonomy.CODEC_AUDIO,
            PlaybackErrorTaxonomy.categorize(
                PlaybackException.ERROR_CODE_UNSPECIFIED,
                "audio/vnd.dts",
            ),
        )
        assertEquals(
            PlaybackErrorTaxonomy.CODEC_VIDEO,
            PlaybackErrorTaxonomy.categorize(
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                "video/dolby-vision",
            ),
        )
    }

    @Test
    fun networkCodesClassifyAsNetwork() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        ).forEach { code ->
            assertEquals(PlaybackErrorTaxonomy.NETWORK, PlaybackErrorTaxonomy.categorize(code, null))
        }
    }

    @Test
    fun sourceCodesClassifyAsSource() {
        listOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        ).forEach { code ->
            assertEquals(PlaybackErrorTaxonomy.SOURCE, PlaybackErrorTaxonomy.categorize(code, null))
        }
    }

    @Test
    fun decoderCodesWithoutMimeStayCodec() {
        assertEquals(
            PlaybackErrorTaxonomy.CODEC_VIDEO,
            PlaybackErrorTaxonomy.categorize(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, null),
        )
        assertEquals(
            PlaybackErrorTaxonomy.CODEC_AUDIO,
            PlaybackErrorTaxonomy.categorize(PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, null),
        )
    }

    @Test
    fun unknownCodeStaysUnknown() {
        assertEquals(
            PlaybackErrorTaxonomy.UNKNOWN,
            PlaybackErrorTaxonomy.categorize(PlaybackException.ERROR_CODE_UNSPECIFIED, null),
        )
    }
}
