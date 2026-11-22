package com.fourseveneightnine.tv.client.home

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.view.Surface
import com.fourseveneightnine.tv.client.appGraph

/** Only the selected Den Home app can supply a surface. Media and access checks stay here. */
class HomePictureService : Service() {
    private var client: Messenger? = null
    private var surface: Surface? = null
    private val pip get() = appGraph.pictureInPicture
    private val main = Handler(Looper.getMainLooper())
    private var death: IBinder.DeathRecipient? = null
    private val expire = Runnable { if (surface == null) stopSelf() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        main.removeCallbacks(expire)
        if (pip.canShowHome()) main.postDelayed(expire, 8_000) else stopSelf(startId)
        return START_NOT_STICKY
    }
    private val messages = Messenger(Handler(Looper.getMainLooper()) { message ->
        val peer = message.replyTo ?: return@Handler true
        val uid = runCatching { packageManager.getApplicationInfo(LauncherRails.PACKAGE, 0).uid }.getOrNull()
        if (message.sendingUid != uid) return@Handler true
        when (message.what) {
            1 -> {
                val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                if (packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                        ?.activityInfo?.packageName == LauncherRails.PACKAGE && pip.canShowHome()) {
                    if (client?.binder != peer.binder) {
                        disconnect()
                        val observer = IBinder.DeathRecipient { main.post { if (client?.binder == peer.binder) disconnect() } }
                        try {
                            peer.binder.linkToDeath(observer, 0)
                            death = observer
                            client = peer
                        } catch (_: RemoteException) { return@Handler true }
                    }
                    reply(peer, 4)
                }
            }
            2 -> if (client?.binder == peer.binder) {
                @Suppress("DEPRECATION")
                val target = message.data.getParcelable<Surface>("surface") ?: return@Handler true
                if (!pip.attachHome(target) { reply(peer, 5) }) {
                    target.release()
                    reply(peer, 5)
                } else {
                    surface?.release()
                    surface = target
                }
            }
            3 -> if (client?.binder == peer.binder) disconnect()
            6 -> if (client?.binder == peer.binder) releaseSurface()
        }
        true
    })

    private fun reply(peer: Messenger, what: Int) {
        try { peer.send(Message.obtain(null, what)) } catch (_: RemoteException) { disconnect() }
    }

    private fun releaseSurface() {
        if (surface != null) pip.detachHome()
        surface?.release()
        surface = null
    }

    private fun disconnect() {
        releaseSurface()
        death?.let { client?.binder?.unlinkToDeath(it, 0) }
        death = null
        client = null
    }

    override fun onBind(intent: Intent): IBinder = messages.binder
    override fun onUnbind(intent: Intent): Boolean { disconnect(); stopSelf(); return false }
    override fun onDestroy() { main.removeCallbacks(expire); disconnect(); super.onDestroy() }
}
