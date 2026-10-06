package com.fourseveneightnine.tv.client

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import com.fourseveneightnine.tv.catalog.AtomicTVTamilMVCatalogCache
import com.fourseveneightnine.tv.client.data.RecentsStore
import com.fourseveneightnine.tv.client.playback.PlaybackSession
import com.fourseveneightnine.tv.discovery.NsdAdvertiser
import com.fourseveneightnine.tv.discovery.ReceiverDeviceIdentity
import com.fourseveneightnine.tv.discovery.ReceiverNetworkMonitor
import com.fourseveneightnine.tv.player.ReceiverEnginePolicy
import com.fourseveneightnine.tv.player.SwappableReceiverController
import com.fourseveneightnine.tv.settings.EncryptedTVSettingsStore
import com.fourseveneightnine.tv.settings.TVSettingsPairingCoordinator
import com.fourseveneightnine.tv.startup.ReceiverDiagnostics
import com.fourseveneightnine.tv.startup.ResumePointStore
import com.fourseveneightnine.tv.ui.ArtworkLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual constructor wiring for the whole app. No DI framework, by decision (plan §13): the
 * existing code is OkHttp, kotlinx.serialization and hand wiring, and an annotation processor buys
 * nothing here.
 *
 * Everything is `by lazy`. The graph is built in `Application.onCreate`, and a television process
 * is created far more often than it is used, so nothing that costs a vendor service call — the
 * engine above all — may be constructed before something asks for it.
 */
internal class AppGraph(private val app: Application) {

    val settingsStore: EncryptedTVSettingsStore by lazy { EncryptedTVSettingsStore(app) }

    val catalogCache: AtomicTVTamilMVCatalogCache by lazy { AtomicTVTamilMVCatalogCache(app) }

    val deviceIdentity: ReceiverDeviceIdentity by lazy {
        ReceiverDeviceIdentity.current(app)
    }

    val advertiser: NsdAdvertiser by lazy { NsdAdvertiser(app) }

    /**
     * The listeners bind 0.0.0.0 and survive an address change; the DNS-SD record does not. A
     * router reboot or DHCP lease change otherwise leaves this box listed but unreachable.
     */
    val networkMonitor: ReceiverNetworkMonitor by lazy {
        ReceiverNetworkMonitor(app) { advertiser.republish() }
    }

    val pairing: TVSettingsPairingCoordinator by lazy {
        TVSettingsPairingCoordinator(
            receiverID = advertiser.receiverUuid,
            store = settingsStore,
            catalogCache = catalogCache,
        )
    }

    val resumeStore: ResumePointStore by lazy { ResumePointStore(app) }

    val recentsStore: RecentsStore by lazy { RecentsStore(app) }

    /**
     * Poster and backdrop bytes. Given a cache directory so a cold start redraws from disk rather
     * than pulling the whole rail over Wi-Fi again — a television process is killed constantly.
     *
     * The next wave replaces the card path with Coil 3 (plan §4.2); the player's waiting screen
     * keeps using this loader either way.
     */
    val artworkLoader: ArtworkLoader by lazy {
        ArtworkLoader(diskCacheDir = File(app.cacheDir, "artwork"))
    }

    val enginePreferences: SharedPreferences by lazy {
        app.getSharedPreferences(ENGINE_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    val presentationPreferences: SharedPreferences by lazy {
        app.getSharedPreferences(PRESENTATION_PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    /**
     * The proxy owns the engine at runtime, so a codec-failure Exo→mpv failover swaps engines
     * without dropping the connection. The brand default is still the tested behaviour; the stored
     * override exists so a device-specific fault can be compared without a rebuild.
     */
    val controller: SwappableReceiverController by lazy {
        val override = enginePreferences.getString(ENGINE_OVERRIDE_KEY, null)
        ReceiverDiagnostics.record("engine.pref.raw", "value=${override ?: "<none>"}")
        val engine = ReceiverEnginePolicy.engine(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            override = override,
        )
        ReceiverDiagnostics.record("graph.controller.created", "engine=$engine")
        SwappableReceiverController(app, engine)
    }

    /** The one owner of the controller. Both the phone and the local Play action go through it. */
    val playback: PlaybackSession by lazy {
        PlaybackSession(
            controller = controller,
            appContext = app,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    private companion object {
        const val ENGINE_PREFERENCES_NAME = "receiver-engine"
        const val ENGINE_OVERRIDE_KEY = "override"
        const val PRESENTATION_PREFERENCES_NAME = "receiver_presentation"
    }
}
