package com.codingagent.mobile.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.codingagent.mobile.CodingAgentApp
import com.codingagent.mobile.R
import com.codingagent.mobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the on-device runtime and agent processes alive.
 */
@AndroidEntryPoint
class AgentRuntimeService : Service() {

    @Inject lateinit var runtimeManager: RuntimeManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    runCatching { runtimeManager.stop() }
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // Foreground promotion must succeed first; on Android 12+ a
                // background start can throw — fail fast instead of leaking
                // a background runtime process.
                if (!startAsForeground()) {
                    Timber.w("Foreground promotion failed; not starting runtime")
                    stopSelf()
                    return START_NOT_STICKY
                }
                scope.launch {
                    try {
                        runtimeManager.start()
                    } catch (e: Exception) {
                        Timber.e(e, "Failed to start runtime from service")
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(): Boolean {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentRuntimeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CodingAgentApp.CHANNEL_RUNTIME)
            .setContentTitle(getString(R.string.notification_runtime_title))
            .setContentText(getString(R.string.notification_runtime_text))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "startForeground failed")
            false
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.codingagent.mobile.STOP_RUNTIME"
        private const val NOTIFICATION_ID = 1001
    }
}
