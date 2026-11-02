package com.huntercoles.splatman.capture.processing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import javax.inject.Inject

/**
 * Foreground service (type dataSync) that runs [ProcessingRepository.process] for a scan folder, with a progress
 * notification and a Cancel action (ADR 0005 runner). Start it with [start]; it stops itself when no run is left.
 */
@AndroidEntryPoint
class ProcessingService : Service() {

    @Inject
    lateinit var repository: ProcessingRepository

    @Inject
    lateinit var listeners: Set<@JvmSuppressWildcards ProcessingListener>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = ProcessingRuns(
        scope = scope,
        process = { repository.process(it) },
        watch = { scan ->
            repository.state(scan).collect { notificationManager().notify(NOTIFICATION_ID, notification(scan, it)) }
        },
        onFinished = { scan, result, last ->
            listeners.forEach { it.onProcessing(scan.name, result) }
            if (last) {
                stopForeground(STOP_FOREGROUND_DETACH)
                notificationManager().notify(NOTIFICATION_ID, notification(scan, result))
                stopSelf()
            }
        },
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_SCAN)
        if (path == null) {
            if (runs.isEmpty) stopSelf(startId)
            return START_NOT_STICKY
        }
        val scan = File(path)
        when (intent.action) {
            ACTION_CANCEL -> {
                repository.cancel(scan)
                if (runs.isEmpty) stopSelf(startId)
            }
            else -> startRun(scan)
        }
        return START_NOT_STICKY
    }

    private fun startRun(scan: File) {
        goForeground(notification(scan, ProcessingState.Running(null)))
        if (runs.start(scan)) listeners.forEach { it.onProcessing(scan.name, ProcessingState.Running(null)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Processing scans", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return manager
    }

    private fun notification(scan: File, state: ProcessingState): Notification {
        notificationManager()
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Building splat")
            .setOnlyAlertOnce(true)
        when (state) {
            is ProcessingState.Running -> {
                val p = state.progress
                if (p == null || p.totalIterations == 0) {
                    builder.setProgress(0, 0, true).setContentText("Working")
                } else {
                    builder.setProgress(p.totalIterations, p.iteration, false)
                        .setContentText("${p.iteration} / ${p.totalIterations} - ${p.splatCount} splats")
                }
                builder.setOngoing(true).addAction(cancelAction(scan))
            }
            is ProcessingState.Paused ->
                builder.setOngoing(true).setContentText("Paused: phone too hot").addAction(cancelAction(scan))
            is ProcessingState.Done -> builder.setContentTitle("Splat ready").setAutoCancel(true)
            is ProcessingState.Failed -> builder.setContentTitle("Splat failed").setContentText(state.message)
            ProcessingState.Cancelled -> builder.setContentTitle("Processing cancelled").setAutoCancel(true)
            ProcessingState.Idle -> builder.setContentText("Waiting")
        }
        return builder.build()
    }

    private fun cancelAction(scan: File): Notification.Action {
        val intent = Intent(this, ProcessingService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_SCAN, scan.path)
        val pending = PendingIntent.getService(
            this,
            scan.path.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(null, "Cancel", pending).build()
    }

    companion object {
        const val ACTION_START = "com.huntercoles.splatman.processing.START"
        const val ACTION_CANCEL = "com.huntercoles.splatman.processing.CANCEL"
        const val EXTRA_SCAN = "scan"
        private const val CHANNEL_ID = "processing"
        private const val NOTIFICATION_ID = 4301

        /** Starts processing the capture bundle in [scan] (its folder) in the foreground. */
        fun start(context: Context, scan: File) {
            val intent = Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCAN, scan.path)
            context.startForegroundService(intent)
        }
    }
}
