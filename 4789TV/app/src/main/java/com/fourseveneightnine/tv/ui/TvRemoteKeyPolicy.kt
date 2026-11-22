package com.fourseveneightnine.tv.ui

import android.view.KeyEvent
import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class RemoteButtonAction(val title: String, val command: TvRemoteCommand? = null) {
    Default("Default"),
    Navigation("Navigation menu"),
    Search("Search"),
    Settings("Playback settings"),
    PlayPause("Play / pause", TvRemoteCommand.TogglePlayPause),
    SeekBackward("Rewind", TvRemoteCommand.SeekBackward),
    SeekForward("Fast-forward", TvRemoteCommand.SeekForward),
    PictureInPicture("Picture in picture", TvRemoteCommand.PictureInPicture),
    Controls("Player controls", TvRemoteCommand.ShowControls),
    Guide("Live TV guide", TvRemoteCommand.OpenGuide),
    PreviousChannel("Previous channel", TvRemoteCommand.ChannelPrevious),
    NextChannel("Next channel", TvRemoteCommand.ChannelNext),
    Favorite("Favorite live channel", TvRemoteCommand.ToggleFavorite),
}

@Serializable
internal data class RemoteButtonBinding(val keyCode: Int, val playback: Boolean, val action: RemoteButtonAction)

/** Only keys received by the Activity can be learned. System shortcuts are never synthesized. */
internal object RemoteButtonMappings {
    const val PREFERENCE_KEY = "remote_button_mappings"
    private val json = Json { ignoreUnknownKeys = true }
    private val navigationKeys = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)
    private val reservedKeys = setOf(KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_FEATURED_APP_1,
        KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_PROFILE_SWITCH, KeyEvent.KEYCODE_NOTIFICATION,
        KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_9, KeyEvent.KEYCODE_BUTTON_10)
    private val shortcutKeys = setOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_BOOKMARK, KeyEvent.KEYCODE_STAR, KeyEvent.KEYCODE_SEARCH,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_BUTTON_3, KeyEvent.KEYCODE_BUTTON_4, KeyEvent.KEYCODE_BUTTON_9, KeyEvent.KEYCODE_BUTTON_10)

    fun canMap(keyCode: Int, playback: Boolean): Boolean =
        keyCode in shortcutKeys || isReservedKey(keyCode) || (playback && keyCode in navigationKeys)

    fun isReservedKey(keyCode: Int): Boolean = keyCode in reservedKeys

    fun actions(playback: Boolean): List<RemoteButtonAction> = RemoteButtonAction.entries.filter {
        it == RemoteButtonAction.Default || if (playback) it.command != null else it.command == null
    }

    fun parse(raw: String?): List<RemoteButtonBinding> = runCatching {
        json.decodeFromString<List<RemoteButtonBinding>>(raw ?: "[]")
    }.getOrDefault(emptyList()).filter { canMap(it.keyCode, it.playback) && it.action in actions(it.playback) }
        .distinctBy { it.keyCode to it.playback }

    fun encode(bindings: List<RemoteButtonBinding>): String = json.encodeToString(bindings)

    fun read(prefs: SharedPreferences): List<RemoteButtonBinding> = parse(prefs.getString(PREFERENCE_KEY, null))

    fun save(prefs: SharedPreferences, keyCode: Int, playback: Boolean, action: RemoteButtonAction) {
        if (!canMap(keyCode, playback) || action !in actions(playback)) return
        val updated = read(prefs).filterNot { it.keyCode == keyCode && it.playback == playback } +
            if (action == RemoteButtonAction.Default) emptyList() else listOf(RemoteButtonBinding(keyCode, playback, action))
        prefs.edit().putString(PREFERENCE_KEY, encode(updated)).apply()
    }

    fun action(bindings: List<RemoteButtonBinding>, keyCode: Int, playback: Boolean,
        controlsVisible: Boolean, overlayVisible: Boolean): RemoteButtonAction? =
        if (controlsVisible || overlayVisible || !canMap(keyCode, playback)) null
        else bindings.firstOrNull { it.keyCode == keyCode && it.playback == playback }?.action
            ?.takeUnless { it == RemoteButtonAction.Default }

    private val channelPipKeys = intArrayOf(KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN)
    private val extraPipKeys = channelPipKeys + intArrayOf(KeyEvent.KEYCODE_MACRO_1, KeyEvent.KEYCODE_FEATURED_APP_1)

    /** Channel keys have no built-in on-demand action. A viewer's explicit video mappings win. */
    fun moviePipKey(bindings: List<RemoteButtonBinding>, extraButtons: Boolean = false): Int? {
        bindings.firstOrNull { it.playback && it.action == RemoteButtonAction.PictureInPicture }?.let { return it.keyCode }
        return (if (extraButtons) extraPipKeys else channelPipKeys).firstOrNull { code ->
            bindings.none { it.playback && it.keyCode == code }
        }
    }

    fun label(keyCode: Int): String = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "OK"
        KeyEvent.KEYCODE_CHANNEL_UP -> "Channel up"
        KeyEvent.KEYCODE_CHANNEL_DOWN -> "Channel down"
        KeyEvent.KEYCODE_MENU -> "Menu"
        KeyEvent.KEYCODE_INFO -> "Info"
        KeyEvent.KEYCODE_GUIDE -> "Guide"
        KeyEvent.KEYCODE_BOOKMARK -> "Bookmark"
        KeyEvent.KEYCODE_STAR -> "Star"
        KeyEvent.KEYCODE_MACRO_1 -> "Star shortcut"
        KeyEvent.KEYCODE_FEATURED_APP_1 -> "Free TV shortcut"
        KeyEvent.KEYCODE_PROFILE_SWITCH -> "Profile shortcut"
        KeyEvent.KEYCODE_NOTIFICATION -> "Dashboard shortcut"
        KeyEvent.KEYCODE_BUTTON_3 -> "YouTube shortcut"
        KeyEvent.KEYCODE_BUTTON_4 -> "Netflix shortcut"
        KeyEvent.KEYCODE_BUTTON_9 -> "Disney+ shortcut"
        KeyEvent.KEYCODE_BUTTON_10 -> "Paramount+ shortcut"
        else -> KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").lowercase().replace('_', ' ')
    }
}

/** Standard Android TV/Google TV/Fire TV remote semantics. Device brands may label the keys
 * differently, but they all report these Android key codes. Keeping the mapping pure makes the
 * behavior testable without coupling playback to one television or remote model. */
internal enum class TvRemoteCommand {
    TogglePlayPause,
    PictureInPicture,
    Play,
    Pause,
    SeekBackward,
    SeekForward,
    ChannelPrevious,
    ChannelNext,
    SelectPreviousChannel,
    SelectNextChannel,
    CommitChannelSelection,
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

        if (livePlayback && keyCode == KeyEvent.KEYCODE_BACK) return TvRemoteCommand.OpenGuide

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
                KeyEvent.KEYCODE_DPAD_RIGHT -> return TvRemoteCommand.ChannelNext
                KeyEvent.KEYCODE_DPAD_UP -> return TvRemoteCommand.OpenGuide
                KeyEvent.KEYCODE_DPAD_LEFT -> return TvRemoteCommand.ChannelPrevious
                KeyEvent.KEYCODE_DPAD_DOWN -> return TvRemoteCommand.ShowControls
            }
        }

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> if (livePlayback) TvRemoteCommand.ShowControls else TvRemoteCommand.TogglePlayPause

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


/** Native repeats move selection; one matching release commits it. No repeated stream opens. */
internal class ChannelHoldGesture {
    private var key: Int? = null
    private var lastStepAt = 0L
    fun begin(keyCode: Int, eventTime: Long) { key = keyCode; lastStepAt = eventTime }
    fun repeat(keyCode: Int, eventTime: Long): Boolean {
        if (key != keyCode || eventTime - lastStepAt < 120L) return false
        lastStepAt = eventTime
        return true
    }
    fun release(keyCode: Int): Boolean {
        if (key != keyCode) return false
        reset()
        return true
    }
    fun reset() { key = null; lastStepAt = 0L }
}
