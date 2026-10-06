package com.fourseveneightnine.tv.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.fourseveneightnine.tv.player.ExternalPlayerIntentPolicy
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches an external player and hands the file to the system installer.
 *
 * THIS FILE IS COMPILED BY THE DIRECT CHANNELS ONLY (`sideload` and `googleTv`).
 *
 * The Play channel compiles the same-named stub from `app/src/installer-stub/java/`. Google Play
 * forbids an app that downloads and installs other apps. So the Play build must not merely disable
 * this path at runtime — the code and its download links must never reach the APK. A runtime flag
 * would leave the URLs sitting in the dex, readable by a store reviewer with `strings`, and the
 * download would still be one reachable branch away.
 *
 * `scripts/check-android-foundation.sh` greps the built Play dex for these download hosts, so the
 * split is enforced mechanically rather than trusted.
 */
internal object PlayerInstaller {

    /** True when this build may fetch and install another app. False in the stub. */
    const val IS_SUPPORTED = true

    /**
     * Labels for the action chooser. The "Install Player" literal lives here, not in the shared
     * shell, so it is absent from the Play dex. A string constant compiles into the APK even when
     * the branch using it can never run, so the text has to sit on this side of the split rather
     * than behind a runtime check.
     */
    val ACTION_LABELS = arrayOf("Open Player", "Install Player")

    /**
     * @param scope the caller's scope; the download is cancelled with it.
     * @param onStatus one line of copy for whatever is drawing progress.
     * @param onProgress 0 to 100 while downloading, and -1 when there is nothing left to show.
     */
    fun downloadAndInstall(
        activity: Activity,
        scope: CoroutineScope,
        player: ExternalPlayerIntentPolicy.PlayerTargetInfo,
        onStatus: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ) {
        val is64Bit = Build.SUPPORTED_ABIS.any { it.contains("arm64") || it.contains("x86_64") }
        val abiTag = if (is64Bit) "arm64-v8a" else "armeabi-v7a"

        val downloadUrl = when (player.packageName) {
            ExternalPlayerIntentPolicy.PACKAGE_NEXT_PLAYER ->
                "https://github.com/anilbeesetti/nextplayer/releases/download/v0.8.0/nextplayer-v0.8.0-$abiTag.apk"
            ExternalPlayerIntentPolicy.PACKAGE_JUST_PLAYER ->
                "https://github.com/brouken/just-player/releases/download/v0.163/Just.Player.v0.163.apk"
            ExternalPlayerIntentPolicy.PACKAGE_VLC ->
                "https://get.videolan.org/vlc-android/3.5.4/VLC-Android-3.5.4-$abiTag.apk"
            ExternalPlayerIntentPolicy.PACKAGE_KODI ->
                if (is64Bit) {
                    "https://mirrors.kodi.tv/releases/android/arm64-v8a/kodi-21.0-Omega-arm64-v8a.apk"
                } else {
                    "https://mirrors.kodi.tv/releases/android/arm/kodi-21.0-Omega-armeabi-v7a.apk"
                }
            else -> "https://tivimate.com/tivimate.apk"
        }

        onProgress(0)
        onStatus("Downloading the ${player.label} installer")

        scope.launch(Dispatchers.IO) {
            val apkFile = File(activity.cacheDir, "${player.packageName}.apk")
            val downloadResult = runCatching {
                val connection = URL(downloadUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 15_000
                connection.readTimeout = 15_000
                connection.instanceFollowRedirects = true
                connection.connect()

                val totalBytes = connection.contentLengthLong.coerceAtLeast(1L)
                val input = connection.inputStream
                val output = FileOutputStream(apkFile)
                val buffer = ByteArray(8192)
                var bytesRead = 0L
                var read: Int
                var lastProgressTime = 0L

                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgressTime > 100) {
                        lastProgressTime = now
                        val percent = ((bytesRead * 100) / totalBytes).toInt().coerceIn(0, 100)
                        val readMb = String.format("%.1f", bytesRead / 1_048_576.0)
                        val totalMb = String.format("%.1f", totalBytes / 1_048_576.0)
                        withContext(Dispatchers.Main) {
                            onProgress(percent)
                            onStatus("Downloading ${player.label} · $readMb MB of $totalMb MB")
                        }
                    }
                }
                output.flush()
                output.close()
                input.close()
                connection.disconnect()
            }

            withContext(Dispatchers.Main) {
                onProgress(-1)
                if (downloadResult.isSuccess) {
                    onStatus("Opening the ${player.label} installer")
                    try {
                        val apkUri: Uri = FileProvider.getUriForFile(
                            activity,
                            "${activity.packageName}.fileprovider",
                            apkFile,
                        )
                        val installIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                            !activity.packageManager.canRequestPackageInstalls()
                        ) {
                            onStatus(
                                "Allow 'Install Unknown Apps' for 4789 TV in this TV's settings to " +
                                    "finish installing ${player.label}.",
                            )
                            try {
                                val manageIntent =
                                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                                        data = Uri.parse("package:${activity.packageName}")
                                    }
                                activity.startActivity(manageIntent)
                            } catch (_: Exception) {
                                activity.startActivity(installIntent)
                            }
                        } else {
                            activity.startActivity(installIntent)
                        }
                    } catch (e: Exception) {
                        onStatus("The installer could not open: ${e.localizedMessage}")
                    }
                } else {
                    onStatus("The ${player.label} download did not finish. Check the TV network.")
                }
            }
        }
    }
}
