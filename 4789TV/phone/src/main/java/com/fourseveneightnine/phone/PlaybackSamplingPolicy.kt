package com.fourseveneightnine.phone

internal object PlaybackSamplingPolicy {
    // Progress is also saved on lifecycle stop and final teardown. This sampler exists only to
    // bound foreground crash loss; fifteen seconds avoids needless five-second CPU/disk wakeups.
    const val INTERVAL_MILLIS = 15_000L

    fun shouldSample(isPlaying: Boolean, isReleased: Boolean): Boolean = isPlaying && !isReleased
}
