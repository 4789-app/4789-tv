package com.fourseveneightnine.tv.client.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.tvprovider.media.tv.TvContractCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** System entry points for initial channel publication and user card removal. */
class TvHomeChannelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    TvContractCompat.ACTION_INITIALIZE_PROGRAMS -> TvHomeChannelPublisher.reconcile(context)
                    TvContractCompat.ACTION_PREVIEW_PROGRAM_BROWSABLE_DISABLED -> {
                        val id = intent.getLongExtra(TvContractCompat.EXTRA_PREVIEW_PROGRAM_ID, -1L)
                        TvHomeChannelPublisher.suppressProgram(context, id)
                    }
                }
            } finally {
                task.finish()
            }
        }
    }
}
