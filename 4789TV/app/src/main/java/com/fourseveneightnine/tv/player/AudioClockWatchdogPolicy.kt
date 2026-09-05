package com.fourseveneightnine.tv.player

enum class AudioClockRaceAction {
    NONE,
    REOPEN,
    DEMOTE_TO_PCM,
    SURFACE_FAILURE,
}

/**
 * Watchdog policy to detect TV HDMI Audio HAL clock drift (position racing >2.5x real-time)
 * and manage escalating self-healing actions.
 */
class AudioClockWatchdogPolicy(
    private val maxConsecutiveHits: Int = 3,
    private val maxRecoveryAttempts: Int = 2,
    private val maxRateThreshold: Double = 2.5,
) {
    var consecutiveHits: Int = 0
        private set

    var recoveryAttempts: Int = 0
        private set

    var forcedPcmDemote: Boolean = false
        private set

    fun resetSession() {
        consecutiveHits = 0
        recoveryAttempts = 0
        forcedPcmDemote = false
    }

    fun resetConsecutiveHits() {
        consecutiveHits = 0
    }

    fun evaluateSample(
        deltaPosMs: Long,
        deltaWallMs: Long,
        playWhenReady: Boolean,
        playbackSpeed: Float,
    ): AudioClockRaceAction {
        if (!playWhenReady || playbackSpeed != 1.0f || deltaWallMs !in 300L..2000L) {
            consecutiveHits = 0
            return AudioClockRaceAction.NONE
        }

        val rate = deltaPosMs.toDouble() / deltaWallMs.toDouble()
        if (rate > maxRateThreshold) {
            consecutiveHits++
        } else {
            consecutiveHits = 0
        }

        if (consecutiveHits >= maxConsecutiveHits) {
            consecutiveHits = 0
            recoveryAttempts++
            return when (recoveryAttempts) {
                1 -> AudioClockRaceAction.REOPEN
                2 -> {
                    forcedPcmDemote = true
                    AudioClockRaceAction.DEMOTE_TO_PCM
                }
                else -> AudioClockRaceAction.SURFACE_FAILURE
            }
        }

        return AudioClockRaceAction.NONE
    }
}
