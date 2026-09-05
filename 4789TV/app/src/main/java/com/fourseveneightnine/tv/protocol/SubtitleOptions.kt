package com.fourseveneightnine.tv.protocol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A subtitle the phone found in the viewer's addons but did not load — offered on the television so
 * the Subs picker can show more than whatever the file happens to ship with.
 *
 * The phone does the searching, because that is where the addon list, the API keys and the
 * release-matching live. The receiver only ever gets a shortlist of labelled URLs, and only fetches
 * one if somebody picks it.
 */
data class SubtitleOption(
    val label: String,
    val url: String,
    val language: String = "",
)

/** The shortlist for the current title. Written by the RPC layer, read by the Activity. */
object SubtitleOptions {
    private val _options = MutableStateFlow<List<SubtitleOption>>(emptyList())
    val options: StateFlow<List<SubtitleOption>> = _options.asStateFlow()

    /** Bounded: a picker nobody can reach the bottom of with a D-pad is not a picker. */
    const val MAX_OPTIONS = 12
    const val MAX_LABEL_LENGTH = 120

    fun stage(options: List<SubtitleOption>) {
        _options.value = options
            .mapNotNull { option ->
                val url = NowPlayingArt.sanitize(option.url) ?: return@mapNotNull null
                option.copy(url = url, label = option.label.trim().take(MAX_LABEL_LENGTH))
            }
            .filter { it.label.isNotEmpty() }
            .distinctBy { it.url }
            .take(MAX_OPTIONS)
    }

    fun clear() {
        _options.value = emptyList()
    }
}
