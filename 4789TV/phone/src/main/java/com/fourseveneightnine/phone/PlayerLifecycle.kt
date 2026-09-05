package com.fourseveneightnine.phone

internal interface ReleasablePlayer {
    val currentPositionMillis: Long
    val durationMillis: Long
    fun pause()
    fun stop()
    fun clearMediaItems()
    fun release()
}

internal class PlayerLifecycle(
    private val player: ReleasablePlayer,
    private val recordProgress: (positionMillis: Long, durationMillis: Long) -> Unit,
) {
    private var released = false

    fun pauseForBackground() {
        if (released) return
        saveProgress()
        player.pause()
    }

    fun releaseOnce() {
        if (released) return
        saveProgress()
        released = true
        player.stop()
        player.clearMediaItems()
        player.release()
    }

    fun saveProgress() {
        if (!released) recordProgress(player.currentPositionMillis, player.durationMillis)
    }
}
