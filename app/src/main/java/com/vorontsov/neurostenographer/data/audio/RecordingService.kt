package com.vorontsov.neurostenographer.data.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vorontsov.neurostenographer.MainActivity
import com.vorontsov.neurostenographer.R
import com.vorontsov.neurostenographer.domain.model.CaptureStatus
import com.vorontsov.neurostenographer.domain.model.RecordingSessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps microphone capture alive while the app is not in the foreground. The
 * service only mirrors [RecordingSessionManager]; it never records by itself.
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject
    lateinit var sessionManager: RecordingSessionManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectorJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithNotification(buildNotification(status = "Starting", elapsedMs = 0))
        collectorJob = scope.launch {
            sessionManager.state.collect(::onStateChanged)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (sessionManager.state.value.status == CaptureStatus.IDLE) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        collectorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun onStateChanged(state: RecordingSessionState) {
        when (state.status) {
            CaptureStatus.IDLE, CaptureStatus.ERROR -> stopSelf()
            CaptureStatus.RECORDING -> notify(
                buildNotification("Recording", state.elapsedMs),
            )
            CaptureStatus.PAUSED -> notify(
                buildNotification("Paused", state.elapsedMs),
            )
            CaptureStatus.STOPPING -> notify(
                buildNotification("Finishing", state.elapsedMs),
            )
        }
    }

    private fun startForegroundWithNotification(notification: Notification) {
        // The microphone foreground-service type was introduced in Android 11.
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String, elapsedMs: Long): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NeuroStenographer")
            .setContentText("$status · ${formatElapsed(elapsedMs)}")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Audio recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the microphone is recording"
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun formatElapsed(elapsedMs: Long): String {
        val totalSeconds = elapsedMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1001
    }
}
