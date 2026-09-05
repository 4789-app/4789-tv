package com.fourseveneightnine.tv.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.fourseveneightnine.tv.transport.ReceiverPorts
import java.io.Closeable
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Observable state for the TV's local-network receiver advertisement. */
sealed interface NsdAdvertisementStatus {
    data object Stopped : NsdAdvertisementStatus

    data object Starting : NsdAdvertisementStatus

    data object Stopping : NsdAdvertisementStatus

    data class Advertising(
        val serviceName: String,
    ) : NsdAdvertisementStatus

    data class Error(
        val message: String,
    ) : NsdAdvertisementStatus
}

/**
 * Activity-owned DNS-SD advertising for the Kodi-compatible receiver endpoint.
 *
 * The advertiser only retains [Context.getApplicationContext], so a pending NSD callback cannot
 * retain an Activity. Calls to [start], [stop], and [close] are idempotent. The active
 * [Registration.listener] remains reachable until Android confirms unregistration, including
 * after an unregistration failure, so retries never create a duplicate advertisement.
 */
class NsdAdvertiser(
    context: Context,
) : Closeable {
    private val applicationContext = context.applicationContext
    private val deviceIdentity = ReceiverDeviceIdentity.current(applicationContext)
    private val nsdManager = applicationContext.getSystemService(NsdManager::class.java)
    private val multicastLock = runCatching {
        applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock(MULTICAST_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }.getOrNull()
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()
    private val retryHandler = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow<NsdAdvertisementStatus>(NsdAdvertisementStatus.Stopped)

    val status: StateFlow<NsdAdvertisementStatus> = _status.asStateFlow()
    val receiverUuid: String = persistedUuid()

    private var state = NsdAdvertisementState()
    private var activeRegistration: Registration? = null
    private var pendingUnregisterRetry: PendingUnregisterRetry? = null

    /** Starts advertising, returning false only after this advertiser has been closed. */
    fun start(): Boolean {
        val operations = synchronized(lock) {
            if (state.closed) {
                null
            } else {
                val transition = NsdAdvertisementPlanner.start(state)
                state = transition.state
                when (transition.command) {
                    NsdAdvertisementCommand.Register -> {
                        cancelPendingUnregisterRetryLocked()
                        val registration = newRegistrationLocked()
                        activeRegistration = registration
                        _status.value = NsdAdvertisementStatus.Starting
                        Operations(register = registration)
                    }

                    NsdAdvertisementCommand.Unregister -> {
                        _status.value = NsdAdvertisementStatus.Stopping
                        Operations(unregister = activeRegistration)
                    }

                    NsdAdvertisementCommand.None -> {
                        if (state.registration?.stopRequested == true) {
                            _status.value = NsdAdvertisementStatus.Stopping
                        }
                        Operations()
                    }

                    NsdAdvertisementCommand.ScheduleUnregisterRetry -> {
                        val registration = activeRegistration
                        val delayMillis = transition.retryDelayMillis
                        if (registration == null || delayMillis == null) {
                            Operations()
                        } else {
                            Operations(
                                scheduleUnregisterRetry = UnregisterRetry(
                                    registration = registration,
                                    delayMillis = delayMillis,
                                ),
                            )
                        }
                    }
                }
            }
        } ?: return false

        // Android 12 and older do not automatically enable Wi-Fi multicast reception for
        // foreground NSD operations. Sony Android TVs commonly run those releases, so hold the
        // lock only while this visible receiver wants to advertise itself.
        acquireMulticastReception()
        operations.unregister?.let(::unregister)
        operations.register?.let(::register)
        operations.scheduleUnregisterRetry?.let(::scheduleUnregisterRetry)
        return true
    }

    /**
     * Re-registers the advertisement so it publishes the box's current address.
     *
     * Safe to call at any time: [stop] then [start] is exactly the sequence the planner already
     * handles, so the re-register waits for Android to confirm the old registration is gone rather
     * than racing a second listener. A call while stopped simply starts advertising.
     */
    fun republish(): Boolean {
        if (synchronized(lock) { state.closed }) return false
        stop()
        return start()
    }

    /** Requests unregistration while retaining the listener until Android confirms it. */
    fun stop() {
        val registrationToUnregister = synchronized(lock) {
            val transition = NsdAdvertisementPlanner.stop(state)
            state = transition.state
            if (state.registration == null) {
                cancelPendingUnregisterRetryLocked()
                _status.value = NsdAdvertisementStatus.Stopped
                null
            } else {
                _status.value = NsdAdvertisementStatus.Stopping
                activeRegistration.takeIf { transition.command == NsdAdvertisementCommand.Unregister }
            }
        }
        registrationToUnregister?.let(::unregister)
        releaseMulticastReception()
    }

    /** Prevents future registrations and releases the current service if Android still has one. */
    override fun close() {
        val registrationToUnregister = synchronized(lock) {
            val transition = NsdAdvertisementPlanner.close(state)
            state = transition.state
            if (state.registration == null) {
                cancelPendingUnregisterRetryLocked()
                _status.value = NsdAdvertisementStatus.Stopped
                null
            } else {
                _status.value = NsdAdvertisementStatus.Stopping
                activeRegistration.takeIf { transition.command == NsdAdvertisementCommand.Unregister }
            }
        }
        registrationToUnregister?.let(::unregister)
        releaseMulticastReception()
    }

    private fun listenerFor(registration: Registration): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registrationFailed(registration, "Registration failed (code $errorCode).")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                serviceRegistered(registration, serviceInfo)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                val registrationToRegister = synchronized(lock) {
                    if (activeRegistration !== registration) return

                    cancelPendingUnregisterRetryLocked(registration)
                    val transition = NsdAdvertisementPlanner.serviceUnregistered(state)
                    state = transition.state
                    activeRegistration = null
                    if (transition.command == NsdAdvertisementCommand.Register) {
                        newRegistrationLocked().also { next ->
                            activeRegistration = next
                            _status.value = NsdAdvertisementStatus.Starting
                        }
                    } else {
                        _status.value = NsdAdvertisementStatus.Stopped
                        null
                    }
                }
                registrationToRegister?.let(::register)
                if (registrationToRegister == null) releaseMulticastReception()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                unregistrationFailed(registration, "Unregistration failed (code $errorCode).")
            }
        }

    private fun serviceRegistered(registration: Registration, serviceInfo: NsdServiceInfo) {
        val registrationToUnregister = synchronized(lock) {
            if (activeRegistration !== registration) return

            val transition = NsdAdvertisementPlanner.serviceRegistered(state)
            state = transition.state
            if (transition.command == NsdAdvertisementCommand.Unregister) {
                _status.value = NsdAdvertisementStatus.Stopping
                registration
            } else if (state.registration?.stopRequested == true) {
                _status.value = NsdAdvertisementStatus.Stopping
                null
            } else {
                _status.value = NsdAdvertisementStatus.Advertising(serviceInfo.serviceName)
                null
            }
        }
        registrationToUnregister?.let(::unregister)
    }

    private fun register(registration: Registration) {
        try {
            nsdManager.registerService(
                serviceInfo(registration.requestedServiceName),
                NsdManager.PROTOCOL_DNS_SD,
                registration.listener,
            )
        } catch (error: Throwable) {
            registrationFailed(registration, error)
        }
    }

    private fun unregister(registration: Registration) {
        synchronized(lock) {
            if (
                activeRegistration !== registration ||
                state.registration?.unregistrationRequested != true
            ) {
                return
            }
        }

        try {
            nsdManager.unregisterService(registration.listener)
        } catch (error: Throwable) {
            unregistrationFailed(registration, error)
        }
    }

    private fun registrationFailed(registration: Registration, error: Throwable) {
        registrationFailed(registration, "Registration failed.")
    }

    private fun registrationFailed(registration: Registration, message: String) {
        synchronized(lock) {
            if (activeRegistration !== registration) return

            cancelPendingUnregisterRetryLocked(registration)
            state = NsdAdvertisementPlanner.registrationFailed(state).state
            activeRegistration = null
            releaseMulticastReception()
            _status.value = if (state.closed || !state.advertisingDesired) {
                NsdAdvertisementStatus.Stopped
            } else {
                NsdAdvertisementStatus.Error(message)
            }
        }
    }

    private fun unregistrationFailed(registration: Registration, error: Throwable) {
        unregistrationFailed(registration, "Unregistration failed.")
    }

    private fun unregistrationFailed(registration: Registration, message: String) {
        val retry = synchronized(lock) {
            if (activeRegistration !== registration) return

            // Keep activeRegistration and its listener. Android may still consider the service
            // registered, and all automatic/manual retries use this exact listener safely.
            val transition = NsdAdvertisementPlanner.unregistrationFailed(state)
            state = transition.state
            _status.value = if (
                transition.command == NsdAdvertisementCommand.ScheduleUnregisterRetry
            ) {
                NsdAdvertisementStatus.Stopping
            } else {
                NsdAdvertisementStatus.Error(message)
            }
            if (
                transition.command == NsdAdvertisementCommand.ScheduleUnregisterRetry &&
                transition.retryDelayMillis != null
            ) {
                UnregisterRetry(
                    registration = registration,
                    delayMillis = transition.retryDelayMillis,
                )
            } else {
                null
            }
        }
        retry?.let(::scheduleUnregisterRetry)
    }

    private fun scheduleUnregisterRetry(retry: UnregisterRetry) {
        synchronized(lock) {
            if (activeRegistration !== retry.registration) return
            if (pendingUnregisterRetry != null) return

            val runnable = Runnable {
                runUnregisterRetry(retry.registration)
            }
            pendingUnregisterRetry = PendingUnregisterRetry(
                registration = retry.registration,
                runnable = runnable,
            )
            retryHandler.postDelayed(runnable, retry.delayMillis)
        }
    }

    private fun runUnregisterRetry(registration: Registration) {
        val shouldUnregister = synchronized(lock) {
            val pending = pendingUnregisterRetry
            if (pending == null || pending.registration !== registration) return

            pendingUnregisterRetry = null
            val transition = NsdAdvertisementPlanner.unregistrationRetry(state)
            state = transition.state
            if (activeRegistration !== registration) {
                false
            } else if (transition.command == NsdAdvertisementCommand.Unregister) {
                _status.value = NsdAdvertisementStatus.Stopping
                true
            } else {
                false
            }
        }
        if (shouldUnregister) unregister(registration)
    }

    private fun cancelPendingUnregisterRetryLocked(registration: Registration? = null) {
        val pending = pendingUnregisterRetry ?: return
        if (registration != null && pending.registration !== registration) return

        retryHandler.removeCallbacks(pending.runnable)
        pendingUnregisterRetry = null
    }

    private fun acquireMulticastReception() {
        runCatching {
            val lock = multicastLock ?: return
            if (!lock.isHeld) lock.acquire()
        }
    }

    private fun releaseMulticastReception() {
        runCatching {
            val lock = multicastLock ?: return
            if (lock.isHeld) lock.release()
        }
    }

    private fun newRegistrationLocked(): Registration =
        Registration(serviceName()).also { registration ->
            registration.listener = listenerFor(registration)
        }

    private fun serviceInfo(serviceName: String): NsdServiceInfo =
        NsdServiceInfo().apply {
            setServiceName(serviceName)
            setServiceType(SERVICE_TYPE)
            setPort(ReceiverPorts.HTTP)
            setAttribute(TXT_RECEIVER_KEY, TXT_RECEIVER_VALUE)
            setAttribute(TXT_UUID_KEY, receiverUuid)
            deviceIdentity.manufacturer.takeIf { it.isNotBlank() }?.let {
                setAttribute(TXT_MANUFACTURER_KEY, utf8Prefix(it, TXT_VALUE_BYTE_LIMIT))
            }
            deviceIdentity.model.takeIf { it.isNotBlank() }?.let {
                setAttribute(TXT_MODEL_KEY, utf8Prefix(it, TXT_VALUE_BYTE_LIMIT))
            }
            setAttribute(TXT_DEVICE_KIND_KEY, deviceIdentity.kind)
            deviceIdentity.deviceName?.takeIf { it.isNotBlank() }?.let {
                setAttribute(TXT_DEVICE_NAME_KEY, utf8Prefix(it, TXT_VALUE_BYTE_LIMIT))
            }
        }

    private fun serviceName(): String =
        "${utf8Prefix(deviceIdentity.displayName, SERVICE_LABEL_BYTE_LIMIT)} · ${receiverUuid.take(SERVICE_SUFFIX_LENGTH)}"

    private fun utf8Prefix(value: String, maximumBytes: Int): String {
        var bytes = 0
        val result = StringBuilder()
        val codePoints = value.codePoints().iterator()
        while (codePoints.hasNext()) {
            val fragment = String(Character.toChars(codePoints.nextInt()))
            val fragmentBytes = fragment.toByteArray(Charsets.UTF_8).size
            if (bytes + fragmentBytes > maximumBytes) break
            result.append(fragment)
            bytes += fragmentBytes
        }
        return result.toString().trim()
    }

    private fun persistedUuid(): String {
        val saved = preferences.getString(PREFERENCE_UUID, null)
        if (saved != null && runCatching { UUID.fromString(saved) }.isSuccess) return saved

        return UUID.randomUUID().toString().also { uuid ->
            preferences.edit().putString(PREFERENCE_UUID, uuid).apply()
        }
    }

    private data class Operations(
        val register: Registration? = null,
        val unregister: Registration? = null,
        val scheduleUnregisterRetry: UnregisterRetry? = null,
    )

    private data class UnregisterRetry(
        val registration: Registration,
        val delayMillis: Long,
    )

    private data class PendingUnregisterRetry(
        val registration: Registration,
        val runnable: Runnable,
    )

    private class Registration(
        val requestedServiceName: String,
    ) {
        lateinit var listener: NsdManager.RegistrationListener
    }

    private companion object {
        const val MULTICAST_LOCK_TAG = "4789-tv-nsd"
        const val PREFERENCES_NAME = "4789_tv_discovery"
        const val PREFERENCE_UUID = "receiver_uuid"
        const val SERVICE_TYPE = "_xbmc-jsonrpc-h._tcp"
        const val SERVICE_SUFFIX_LENGTH = 8
        const val SERVICE_LABEL_BYTE_LIMIT = 48
        const val TXT_VALUE_BYTE_LIMIT = 200
        const val TXT_RECEIVER_KEY = "x4789"
        const val TXT_RECEIVER_VALUE = "1"
        const val TXT_UUID_KEY = "uuid"
        const val TXT_MANUFACTURER_KEY = "maker"
        const val TXT_MODEL_KEY = "model"
        const val TXT_DEVICE_KIND_KEY = "kind"
        const val TXT_DEVICE_NAME_KEY = "device"
    }
}
