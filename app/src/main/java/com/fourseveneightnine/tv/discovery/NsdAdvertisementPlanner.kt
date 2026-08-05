package com.fourseveneightnine.tv.discovery

/**
 * Android-free desired-state policy for one NSD registration.
 *
 * Android requires the same listener object to unregister the registration that created it. A
 * restart therefore waits for the old registration's unregistration callback instead of creating
 * a second registration while the old listener may still be active.
 */
internal data class NsdAdvertisementState(
    val advertisingDesired: Boolean = false,
    val closed: Boolean = false,
    val registration: NsdRegistrationState? = null,
)

internal data class NsdRegistrationState(
    val registered: Boolean = false,
    val stopRequested: Boolean = false,
    val unregistrationRequested: Boolean = false,
    val unregistrationRetryAttempt: Int = 0,
    val unregistrationRetryScheduled: Boolean = false,
)

internal enum class NsdAdvertisementCommand {
    None,
    Register,
    Unregister,
    ScheduleUnregisterRetry,
}

internal data class NsdAdvertisementTransition(
    val state: NsdAdvertisementState,
    val command: NsdAdvertisementCommand = NsdAdvertisementCommand.None,
    val retryDelayMillis: Long? = null,
)

internal object NsdAdvertisementPlanner {
    /**
     * After the initial unregister request fails, at most four automatic retries are scheduled.
     * The retry delays are deliberately short and bounded: 250 ms, 500 ms, 1,000 ms, and
     * 2,000 ms (five unregister calls total, including the initial request). A later
     * start/stop/close request may begin a new four-retry cycle after exhaustion.
     */
    internal val unregistrationRetryDelaysMillis = listOf(250L, 500L, 1_000L, 2_000L)

    fun start(state: NsdAdvertisementState): NsdAdvertisementTransition {
        if (state.closed) return NsdAdvertisementTransition(state)

        val registration = state.registration
            ?: return NsdAdvertisementTransition(
                state = state.copy(
                    advertisingDesired = true,
                    registration = NsdRegistrationState(),
                ),
                command = NsdAdvertisementCommand.Register,
            )

        val desired = state.copy(advertisingDesired = true)
        return if (
            registration.stopRequested &&
            registration.registered &&
            !registration.unregistrationRequested &&
            !registration.unregistrationRetryScheduled
        ) {
            requestUnregistration(desired)
        } else {
            NsdAdvertisementTransition(desired)
        }
    }

    fun stop(state: NsdAdvertisementState): NsdAdvertisementTransition =
        requestStop(state.copy(advertisingDesired = false))

    fun close(state: NsdAdvertisementState): NsdAdvertisementTransition =
        requestStop(
            state.copy(
                advertisingDesired = false,
                closed = true,
            ),
        )

    fun serviceRegistered(state: NsdAdvertisementState): NsdAdvertisementTransition {
        val registration = state.registration ?: return NsdAdvertisementTransition(state)
        val registered = registration.copy(registered = true)
        return if (state.closed || !state.advertisingDesired || registered.stopRequested) {
            requestStop(state.copy(registration = registered))
        } else {
            NsdAdvertisementTransition(state.copy(registration = registered))
        }
    }

    fun registrationFailed(state: NsdAdvertisementState): NsdAdvertisementTransition =
        NsdAdvertisementTransition(state.copy(registration = null))

    fun serviceUnregistered(state: NsdAdvertisementState): NsdAdvertisementTransition =
        if (state.advertisingDesired && !state.closed) {
            NsdAdvertisementTransition(
                state = state.copy(registration = NsdRegistrationState()),
                command = NsdAdvertisementCommand.Register,
            )
        } else {
            NsdAdvertisementTransition(state.copy(registration = null))
        }

    fun unregistrationFailed(state: NsdAdvertisementState): NsdAdvertisementTransition {
        val registration = state.registration ?: return NsdAdvertisementTransition(state)
        if (
            !registration.stopRequested ||
            !registration.registered ||
            !registration.unregistrationRequested ||
            registration.unregistrationRetryScheduled
        ) {
            return NsdAdvertisementTransition(state)
        }

        val retryAttempt = registration.unregistrationRetryAttempt + 1
        val retryDelay = unregistrationRetryDelaysMillis.getOrNull(retryAttempt - 1)
        return NsdAdvertisementTransition(
            state.copy(
                registration = registration.copy(
                    stopRequested = true,
                    unregistrationRequested = false,
                    unregistrationRetryAttempt = retryAttempt,
                    unregistrationRetryScheduled = retryDelay != null,
                ),
            ),
            command = if (retryDelay == null) {
                NsdAdvertisementCommand.None
            } else {
                NsdAdvertisementCommand.ScheduleUnregisterRetry
            },
            retryDelayMillis = retryDelay,
        )
    }

    fun unregistrationRetry(state: NsdAdvertisementState): NsdAdvertisementTransition {
        val registration = state.registration ?: return NsdAdvertisementTransition(state)
        if (
            !registration.stopRequested ||
            !registration.registered ||
            !registration.unregistrationRetryScheduled ||
            registration.unregistrationRequested
        ) {
            return NsdAdvertisementTransition(state)
        }

        return NsdAdvertisementTransition(
            state = state.copy(
                registration = registration.copy(
                    unregistrationRequested = true,
                    unregistrationRetryScheduled = false,
                ),
            ),
            command = NsdAdvertisementCommand.Unregister,
        )
    }

    private fun requestStop(state: NsdAdvertisementState): NsdAdvertisementTransition =
        requestUnregistration(state)

    private fun requestUnregistration(state: NsdAdvertisementState): NsdAdvertisementTransition {
        val registration = state.registration ?: return NsdAdvertisementTransition(state)
        val stopping = registration.copy(stopRequested = true)
        return if (
            stopping.registered &&
            !stopping.unregistrationRequested &&
            !stopping.unregistrationRetryScheduled
        ) {
            NsdAdvertisementTransition(
                state = state.copy(
                    registration = stopping.copy(
                        unregistrationRequested = true,
                        unregistrationRetryAttempt = 0,
                    ),
                ),
                command = NsdAdvertisementCommand.Unregister,
            )
        } else {
            NsdAdvertisementTransition(state.copy(registration = stopping))
        }
    }
}
