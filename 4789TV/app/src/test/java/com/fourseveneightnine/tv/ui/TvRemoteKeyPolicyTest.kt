package com.fourseveneightnine.tv.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TvRemoteKeyPolicyTest {
    @Test fun openingControlsConsumesOnlyTheOpeningSelectRelease() {
        val gesture = PlayerControlOpeningGesture()
        gesture.openedWith(KeyEvent.KEYCODE_DPAD_CENTER, TvRemoteCommand.ShowControls)
        assertEquals(false, gesture.consumeRelease(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(true, gesture.consumeRelease(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(false, gesture.consumeRelease(KeyEvent.KEYCODE_DPAD_CENTER))
        gesture.openedWith(KeyEvent.KEYCODE_DPAD_CENTER, TvRemoteCommand.PassThrough)
        assertEquals(false, gesture.consumeRelease(KeyEvent.KEYCODE_DPAD_CENTER))
        gesture.openedWith(KeyEvent.KEYCODE_DPAD_DOWN, TvRemoteCommand.ShowControls)
        assertEquals(false, gesture.consumeRelease(KeyEvent.KEYCODE_DPAD_DOWN))
    }

    @Test fun dedicatedFavoriteKeysWorkWithPlayerChromeOpenOrClosed() {
        for (key in listOf(KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_STAR)) {
            assertEquals(TvRemoteCommand.ToggleFavorite,
                TvRemoteKeyPolicy.command(key, playbackActive = true, controlsVisible = false))
            assertEquals(TvRemoteCommand.ToggleFavorite,
                TvRemoteKeyPolicy.command(key, playbackActive = true, controlsVisible = true))
            assertEquals(TvRemoteCommand.PassThrough,
                TvRemoteKeyPolicy.command(key, playbackActive = false))
        }
    }

    @Test
    fun dpadAndDedicatedMediaKeysShareUniversalPlaybackCommands() {
        assertEquals(
            TvRemoteCommand.ShowControls,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_CENTER, playbackActive = true),
        )
        assertEquals(
            TvRemoteCommand.SeekBackward,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_MEDIA_REWIND, playbackActive = true),
        )
        assertEquals(
            TvRemoteCommand.SeekForward,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_RIGHT, playbackActive = true),
        )
        assertEquals(
            TvRemoteCommand.Pause,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_MEDIA_PAUSE, playbackActive = true),
        )
    }

    @Test fun liveDpadOpensGuideWithoutSeekingOrPausing() {
        for (key in listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP)) {
            assertEquals(TvRemoteCommand.OpenGuide, TvRemoteKeyPolicy.command(key, true, livePlayback = true))
            assertEquals(TvRemoteCommand.PassThrough, TvRemoteKeyPolicy.command(key, true, true, true))
        }
        assertEquals(TvRemoteCommand.ChannelPrevious, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_LEFT, true, livePlayback = true))
        assertEquals(TvRemoteCommand.ShowControls, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_DOWN, true, livePlayback = true))
        assertEquals(TvRemoteCommand.ShowControls, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_CENTER, true, livePlayback = true))
    }

    @Test
    fun backStopsPlaybackWithoutExitingTheReceiver() {
        assertEquals(
            TvRemoteCommand.Stop,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_BACK, playbackActive = true),
        )
        assertEquals(
            TvRemoteCommand.PassThrough,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_BACK, playbackActive = false),
        )
    }

    @Test
    fun theOnScreenControlsOwnTheDpadWhileTheyAreVisible() {
        // Otherwise a scrub on the bar ALSO fired a blind ±10s seek behind it.
        assertEquals(
            TvRemoteCommand.PassThrough,
            TvRemoteKeyPolicy.command(
                KeyEvent.KEYCODE_DPAD_RIGHT,
                playbackActive = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvRemoteCommand.PassThrough,
            TvRemoteKeyPolicy.command(
                KeyEvent.KEYCODE_DPAD_CENTER,
                playbackActive = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun backDismissesTheControlsBeforeItStopsAnything() {
        assertEquals(
            TvRemoteCommand.HideControls,
            TvRemoteKeyPolicy.command(
                KeyEvent.KEYCODE_BACK,
                playbackActive = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvRemoteCommand.Stop,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_BACK, playbackActive = true),
        )
    }

    @Test
    fun dedicatedMediaKeysKeepWorkingUnderTheControls() {
        assertEquals(
            TvRemoteCommand.TogglePlayPause,
            TvRemoteKeyPolicy.command(
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                playbackActive = true,
                controlsVisible = true,
            ),
        )
        assertEquals(
            TvRemoteCommand.Stop,
            TvRemoteKeyPolicy.command(
                KeyEvent.KEYCODE_MEDIA_STOP,
                playbackActive = true,
                controlsVisible = true,
            ),
        )
    }

    @Test
    fun channelKeysWorkWithOrWithoutTheLiveControlBar() {
        for (controlsVisible in listOf(false, true)) {
            assertEquals(TvRemoteCommand.ChannelPrevious,
                TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_CHANNEL_DOWN, true, controlsVisible))
            assertEquals(TvRemoteCommand.ChannelNext,
                TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_CHANNEL_UP, true, controlsVisible))
            assertEquals(TvRemoteCommand.OpenGuide,
                TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_GUIDE, true, controlsVisible))
        }
        assertEquals(TvRemoteCommand.PassThrough,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_CHANNEL_UP, playbackActive = false))
    }

    @Test
    fun diagnosticsRemainsAvailableWhileIdle() {
        assertEquals(
            TvRemoteCommand.ToggleDiagnostics,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_INFO, playbackActive = false),
        )
    }

    @Test
    fun theSearchKeyOpensThePasteUrlDialogWhetherOrNotSomethingIsPlaying() {
        // Remote's mic/magnifier button — no matching playback action would fit in either state,
        // so it's always the paste-URL surface.
        assertEquals(
            TvRemoteCommand.PasteUrl,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_SEARCH, playbackActive = false),
        )
        assertEquals(
            TvRemoteCommand.PasteUrl,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_SEARCH, playbackActive = true),
        )
    }
}
