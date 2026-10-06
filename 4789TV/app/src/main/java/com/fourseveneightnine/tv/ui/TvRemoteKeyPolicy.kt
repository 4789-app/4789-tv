package com.fourseveneightnine.tv.ui

import android.view.KeyEvent

/** Standard Android TV/Google TV/Fire TV remote semantics. Device brands may label the keys
 * differently, but they all report these Android key codes. Keeping the mapping pure makes the
 * behavior testable without coupling playback to one television or remote model. */
internal enum class TvRemoteCommand {
    TogglePlayPause,
    Play,
    Pause,
    SeekBackward,
    SeekForward,
    ChannelPrevious,
    ChannelNext,
    OpenGuide,
    ToggleFavorite,
    Stop,
    ShowControls,
    HideControls,
    ToggleDiagnostics,
    /** Open the paste-URL dialog. Always available; a paste that arrives during playback stops
     *  the current title, exactly like a fresh phone-initiated open would. */
    PasteUrl,
    PassThrough,
}

internal object TvRemoteKeyPolicy {
    fun isFavoriteKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_BOOKMARK ||
        keyCode == KeyEvent.KEYCODE_STAR

    /**
     * @param controlsVisible whether the on-TV player UI is on screen. While it is, the D-pad
     *   belongs to it — the bar scrubs and the button row navigates — so those keys pass through
     *   to the view instead of being interpreted as blind ±10s seeks. BACK dismisses the bar
     *   rather than stopping the film; a viewer who opened the controls did not ask to stop.
     *   Dedicated media keys keep working either way: they are unambiguous.
     */
    fun command(
        keyCode: Int,
        playbackActive: Boolean,
        controlsVisible: Boolean = false,
        livePlayback: Boolean = false,
    ): TvRemoteCommand {
        if (keyCode == KeyEvent.KEYCODE_INFO || keyCode == KeyEvent.KEYCODE_MENU) {
            return TvRemoteCommand.ToggleDiagnostics
        }
        // SEARCH is the mic/magnifier key on every Fire TV / Android TV remote. The receiver has
        // no search of its own, so co-opting it for "paste a URL" is safe: a viewer who pressed
        // this key during a film cannot have meant "seek 10 s", and the phone owns discovery.
        if (keyCode == KeyEvent.KEYCODE_SEARCH) {
            return TvRemoteCommand.PasteUrl
        }
        if (!playbackActive) return TvRemoteCommand.PassThrough

        if (isFavoriteKey(keyCode)) return TvRemoteCommand.ToggleFavorite

        // Channel keys are dedicated tuning controls even with the player bar raised.
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN) return TvRemoteCommand.ChannelPrevious
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP) return TvRemoteCommand.ChannelNext
        if (keyCode == KeyEvent.KEYCODE_GUIDE) return TvRemoteCommand.OpenGuide

        if (controlsVisible) {
            return when (keyCode) {
                KeyEvent.KEYCODE_BACK -> TvRemoteCommand.HideControls
                KeyEvent.KEYCODE_MEDIA_PLAY -> TvRemoteCommand.Play
                KeyEvent.KEYCODE_MEDIA_PAUSE -> TvRemoteCommand.Pause
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK ->
                    TvRemoteCommand.TogglePlayPause

                KeyEvent.KEYCODE_MEDIA_STOP -> TvRemoteCommand.Stop
                else -> TvRemoteCommand.PassThrough
            }
        }

        if (livePlayback) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP -> return TvRemoteCommand.OpenGuide
                KeyEvent.KEYCODE_DPAD_LEFT -> return TvRemoteCommand.ChannelPrevious
                KeyEvent.KEYCODE_DPAD_DOWN -> return TvRemoteCommand.ShowControls
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> TvRemoteCommand.ShowControls

            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> TvRemoteCommand.TogglePlayPause

            KeyEvent.KEYCODE_MEDIA_PLAY -> TvRemoteCommand.Play
            KeyEvent.KEYCODE_MEDIA_PAUSE -> TvRemoteCommand.Pause
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            -> TvRemoteCommand.SeekBackward

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            -> TvRemoteCommand.SeekForward

            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MEDIA_STOP,
            -> TvRemoteCommand.Stop

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> TvRemoteCommand.ShowControls

            else -> TvRemoteCommand.PassThrough
        }
    }
}

/** The press that raises controls must not click the newly focused Play button on release. */
internal class PlayerControlOpeningGesture {
    private var selectKey: Int? = null
    fun openedWith(keyCode: Int, command: TvRemoteCommand) {
        if (command == TvRemoteCommand.ShowControls && keyCode in listOf(
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER))
            selectKey = keyCode
    }
    fun reset() { selectKey = null }
    fun consumeRelease(keyCode: Int): Boolean {
        if (selectKey != keyCode) return false
        selectKey = null
        return true
    }
}
