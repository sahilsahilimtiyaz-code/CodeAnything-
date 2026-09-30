package com.codingagent.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class CodingAgentApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return

        val runtime = NotificationChannel(
            CHANNEL_RUNTIME,
            getString(R.string.channel_runtime),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_runtime_desc)
            setShowBadge(false)
        }

        val agent = NotificationChannel(
            CHANNEL_AGENT,
            getString(R.string.channel_agent),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.channel_agent_desc)
        }

        manager.createNotificationChannels(listOf(runtime, agent))
    }

    companion object {
        const val CHANNEL_RUNTIME = "runtime"
        const val CHANNEL_AGENT = "agent"
    }
}
