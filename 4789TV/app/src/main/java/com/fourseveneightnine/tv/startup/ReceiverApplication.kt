package com.fourseveneightnine.tv.startup

import android.app.Application

class ReceiverApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ReceiverDiagnostics.install(this)
        ReceiverDiagnostics.record(
            stage = "application.onCreate",
            detail = "sdk=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}",
        )
    }
}
