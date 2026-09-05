package com.fourseveneightnine.phone

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.core.net.toUri

@Composable
fun PlaybackScreen(uri: String, titleID: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val progressStore = remember(context) { LocalPlaybackProgressStore(context) }
    val resumeAt = remember(titleID) { progressStore.load(titleID)?.positionMillis ?: 0 }
    val player = remember(uri, resumeAt) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            setMediaItem(MediaItem.fromUri(uri.toUri()))
            prepare()
            if (resumeAt > 0) seekTo(resumeAt)
            playWhenReady = true
        }
    }
    val lifecycle = remember(player, titleID) {
        PlayerLifecycle(
            object : ReleasablePlayer {
                override val currentPositionMillis: Long get() = player.currentPosition
                override val durationMillis: Long get() = player.duration
                override fun pause() = player.pause()
                override fun stop() = player.stop()
                override fun clearMediaItems() = player.clearMediaItems()
                override fun release() = player.release()
            },
            recordProgress = { position, duration ->
                progressStore.record(titleID, position, duration)
            },
        )
    }
    var isPlaying by remember(player) { mutableStateOf(player.isPlaying) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(lifecycle, isPlaying) {
        if (!PlaybackSamplingPolicy.shouldSample(isPlaying, isReleased = false)) {
            return@LaunchedEffect
        }
        while (true) {
            kotlinx.coroutines.delay(PlaybackSamplingPolicy.INTERVAL_MILLIS)
            lifecycle.saveProgress()
        }
    }

    DisposableEffect(lifecycleOwner, lifecycle) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = lifecycle.pauseForBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(lifecycle) {
        onDispose { lifecycle.releaseOnce() }
    }

    Box(Modifier.fillMaxSize().testTag("local-player")) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player } },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize(),
        )
        Button(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopStart).padding(20.dp),
        ) { Text("Close player") }
    }
}
