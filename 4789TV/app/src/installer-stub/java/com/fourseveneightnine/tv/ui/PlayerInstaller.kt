package com.fourseveneightnine.tv.ui

import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy

/**
 * The Play product cannot fetch or install another app, so this build has no installer.
 *
 * THIS FILE IS COMPILED BY THE PLAY PRODUCT ONLY (`:tvplay`).
 *
 * The sideload receiver (`:app`) compiles the working object of the same name from
 * `app/src/installer/java/`. Google Play forbids an app that downloads and installs other apps.
 * Removing the permission is not enough on its own — the download hosts and the install intent
 * must be absent from the shipped dex. Keeping the two products on separate source directories is
 * what makes that true, and `scripts/check-android-foundation.sh` greps the built Play dex to
 * prove it stays true.
 *
 * [IS_SUPPORTED] is false here, so `MainActivity` hides the install action entirely. Nothing calls
 * [downloadAndInstall]; it exists only so both source directories present the same shape.
 */
internal object PlayerInstaller {

    /** False: this build never fetches or installs another app. */
    const val IS_SUPPORTED = false

    /** Open is the only action here, so the chooser is never shown. */
    val ACTION_LABELS = arrayOf("Open Player")

    @Suppress("UNUSED_PARAMETER")
    fun downloadAndInstall(
        activity: MainActivity,
        player: ExternalPlayerIntentPolicy.PlayerTargetInfo,
    ) = Unit
}
