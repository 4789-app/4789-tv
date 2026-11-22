package com.fourseveneightnine.tv.ui

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class TvRemoteKeyPolicyTest {
    @Test fun movieCornerUsesAFreeChannelKeyOrTheViewersShortcut() {
        val up = KeyEvent.KEYCODE_CHANNEL_UP
        val down = KeyEvent.KEYCODE_CHANNEL_DOWN
        val bound = RemoteButtonBinding(up, true, RemoteButtonAction.Controls)
        assertEquals(up, RemoteButtonMappings.moviePipKey(emptyList()))
        assertEquals(down, RemoteButtonMappings.moviePipKey(listOf(bound)))
        assertEquals(null, RemoteButtonMappings.moviePipKey(listOf(bound, bound.copy(keyCode = down))))
        assertEquals(KeyEvent.KEYCODE_MACRO_1, RemoteButtonMappings.moviePipKey(listOf(bound, bound.copy(keyCode = down)), true))
        val chosen = RemoteButtonBinding(KeyEvent.KEYCODE_MACRO_1, true, RemoteButtonAction.PictureInPicture)
        assertEquals(chosen.keyCode, RemoteButtonMappings.moviePipKey(listOf(bound, chosen)))
        assertEquals(RemoteButtonAction.PictureInPicture, RemoteButtonMappings.action(listOf(chosen), chosen.keyCode, true, false, false))
        assertEquals(up, RemoteButtonMappings.moviePipKey(listOf(bound.copy(playback = false))))
    }

    @Test fun reservedShortcutsCanBeLearnedWithoutIncludingNavigationOrDeviceControls() {
        val keys = listOf(KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_FEATURED_APP_1,
            KeyEvent.KEYCODE_PROFILE_SWITCH, KeyEvent.KEYCODE_NOTIFICATION, KeyEvent.KEYCODE_GUIDE,
            KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_9, KeyEvent.KEYCODE_BUTTON_10)
        for (key in keys) {
            assertEquals(true, RemoteButtonMappings.isReservedKey(key))
            assertEquals(true, RemoteButtonMappings.canMap(key, false))
            val binding = RemoteButtonBinding(key, true, RemoteButtonAction.PlayPause)
            assertEquals(listOf(binding), RemoteButtonMappings.parse(RemoteButtonMappings.encode(listOf(binding))))
        }
        for (key in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(false, RemoteButtonMappings.isReservedKey(key))
    }

    @Test fun customMappingsPreserveNavigationAndDeviceButtons() {
        for (key in listOf(KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_A)) {
            assertEquals(false, RemoteButtonMappings.canMap(key, false))
            assertEquals(false, RemoteButtonMappings.canMap(key, true))
        }
        assertEquals(false, RemoteButtonMappings.canMap(KeyEvent.KEYCODE_DPAD_CENTER, false))
        assertEquals(true, RemoteButtonMappings.canMap(KeyEvent.KEYCODE_DPAD_CENTER, true))
        assertEquals(true, RemoteButtonMappings.canMap(KeyEvent.KEYCODE_CHANNEL_UP, false))
    }

    @Test fun customMappingsRoundTripSeparateContextsAndYieldToPlayerMenus() {
        val key = KeyEvent.KEYCODE_CHANNEL_UP
        val bindings = listOf(RemoteButtonBinding(key, false, RemoteButtonAction.Search),
            RemoteButtonBinding(key, true, RemoteButtonAction.PlayPause))
        val saved = RemoteButtonMappings.parse(RemoteButtonMappings.encode(bindings))
        assertEquals(bindings, saved)
        assertEquals(RemoteButtonAction.Search, RemoteButtonMappings.action(saved, key, false, false, false))
        assertEquals(RemoteButtonAction.PlayPause, RemoteButtonMappings.action(saved, key, true, false, false))
        assertEquals(null, RemoteButtonMappings.action(saved, key, true, true, false))
        assertEquals(null, RemoteButtonMappings.action(saved, key, true, false, true))
        assertEquals(null, RemoteButtonMappings.action(saved, KeyEvent.KEYCODE_CHANNEL_DOWN, true, false, false))
    }

    @Test fun corruptOrUnsafeStoredMappingsFallBackToDefault() {
        assertEquals(emptyList<RemoteButtonBinding>(), RemoteButtonMappings.parse("broken"))
        assertEquals(emptyList<RemoteButtonBinding>(), RemoteButtonMappings.parse(null))
        val unsafe = listOf(RemoteButtonBinding(KeyEvent.KEYCODE_BACK, true, RemoteButtonAction.PlayPause),
            RemoteButtonBinding(KeyEvent.KEYCODE_DPAD_UP, false, RemoteButtonAction.Search),
            RemoteButtonBinding(KeyEvent.KEYCODE_CHANNEL_UP, false, RemoteButtonAction.PlayPause))
        assertEquals(emptyList<RemoteButtonBinding>(), RemoteButtonMappings.parse(RemoteButtonMappings.encode(unsafe)))
    }

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
            TvRemoteCommand.TogglePlayPause,
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

    @Test fun liveDpadSwitchesChannelsAndBackOpensTheList() {
        assertEquals(TvRemoteCommand.ChannelNext, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_RIGHT, true, livePlayback = true))
        assertEquals(TvRemoteCommand.ChannelPrevious, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_LEFT, true, livePlayback = true))
        assertEquals(TvRemoteCommand.OpenGuide, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_UP, true, livePlayback = true))
        for (controls in listOf(false, true)) assertEquals(TvRemoteCommand.OpenGuide,
            TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_BACK, true, controls, true))
        assertEquals(TvRemoteCommand.PassThrough, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_RIGHT, true, true, true))
        assertEquals(TvRemoteCommand.ShowControls, TvRemoteKeyPolicy.command(KeyEvent.KEYCODE_DPAD_CENTER, true, livePlayback = true))
    }

    @Test fun aHeldChannelKeyCommitsOnlyItsMatchingReleaseAndBoundsRepeatRate() {
        val gesture = ChannelHoldGesture()
        gesture.begin(KeyEvent.KEYCODE_DPAD_RIGHT, 1_000)
        assertEquals(false, gesture.repeat(KeyEvent.KEYCODE_DPAD_RIGHT, 1_100))
        assertEquals(true, gesture.repeat(KeyEvent.KEYCODE_DPAD_RIGHT, 1_120))
        assertEquals(false, gesture.repeat(KeyEvent.KEYCODE_DPAD_RIGHT, 1_130))
        assertEquals(true, gesture.repeat(KeyEvent.KEYCODE_DPAD_RIGHT, 1_240))
        assertEquals(false, gesture.release(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(true, gesture.release(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(false, gesture.release(KeyEvent.KEYCODE_DPAD_RIGHT))
        gesture.begin(KeyEvent.KEYCODE_CHANNEL_DOWN, 1_241)
        assertEquals(true, gesture.release(KeyEvent.KEYCODE_CHANNEL_DOWN))
        gesture.begin(KeyEvent.KEYCODE_DPAD_LEFT, 1_242)
        gesture.reset()
        assertEquals(false, gesture.release(KeyEvent.KEYCODE_DPAD_LEFT))
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
