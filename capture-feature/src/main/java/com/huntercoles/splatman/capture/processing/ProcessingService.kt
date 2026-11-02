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
import com.huntercoles.splatman.capture.R
import com.huntercoles.splatman.core.navigation.ShellRoutes
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Foreground service (type dataSync) that runs [ProcessingRepository.process] for a scan folder, with a progress
 * notification and a Cancel action (ADR 0005 runner); the notification follows [ProcessingNotice]. Start it with
 * [start]; it stops itself when no run is left.
 */
@AndroidEntryPoint
class ProcessingService : Service() {

    @Inject
    lateinit var repository: ProcessingRepository

    @Inject
    lateinit var listeners: Set<@JvmSuppressWildcards ProcessingListener>

    @Inject
    lateinit var scanNames: ScanNames

    private val names = ConcurrentHashMap<String, String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val runs = ProcessingRuns(
        scope = scope,
        process = { repository.process(it) },
        watch = { scan ->
            scanNames.name(scan.name)?.let { names[scan.name] = it }
            repository.state(scan).collect { state ->
                val notice = ProcessingNotice.of(scan.name, state)
                if (notice is ProcessingNotice.Running) notificationManager().notify(ONGOING_ID, notification(scan, notice))
            }
        },
        onFinished = { scan, result, last ->
            listeners.forEach { it.onProcessing(scan.name, result) }
            if (last) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            val notice = ProcessingNotice.of(scan.name, result)
            if (notice != ProcessingNotice.None) {
                notificationManager().notify(resultId(scan), notification(scan, notice))
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
        notificationManager().cancel(resultId(scan))
        goForeground(notification(scan, ProcessingNotice.Running(null)))
        if (runs.start(scan)) listeners.forEach { it.onProcessing(scan.name, ProcessingState.Running(null)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun notificationManager(): NotificationManager {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        if (manager.getNotificationChannel(RESULT_CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    getString(R.string.notif_channel_result),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
        return manager
    }

    private fun notification(scan: File, notice: ProcessingNotice): Notification {
        notificationManager()
        val name = names[scan.name] ?: getString(R.string.notif_scan_fallback)
        return when (notice) {
            is ProcessingNotice.Running -> Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setContentTitle(getString(R.string.notif_running, name, notice.percent ?: 0))
                .apply { if (notice.hot) setContentText(getString(R.string.notif_paused)) }
                .setProgress(PERCENT, notice.percent ?: 0, notice.percent == null)
                .setContentIntent(open(scan, ShellRoutes.path(processing(scan)).orEmpty()))
                .addAction(cancelAction(scan))
                .build()
            is ProcessingNotice.Done -> Notification.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(getString(R.string.notif_done, name))
                .setAutoCancel(true)
                .setContentIntent(open(scan, notice.tapRoute))
                .build()
            is ProcessingNotice.Failed -> Notification.Builder(this, RESULT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(getString(R.string.notif_failed, name))
                .setAutoCancel(true)
                .setContentIntent(open(scan, notice.tapRoute))
                .build()
            ProcessingNotice.None -> Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .build()
        }
    }

    private fun processing(scan: File) =
        com.huntercoles.splatman.core.navigation.NavigationDestination.Processing(scan.name)

    /** Opens the app at [route] (MainActivity reads [ShellRoutes.EXTRA_ROUTE] on a fresh start). */
    private fun open(scan: File, route: String): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        launch.putExtra(ShellRoutes.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(
            this,
            (scan.path + route).hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
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
        return Notification.Action.Builder(null, getString(R.string.notif_cancel), pending).build()
    }

    private fun resultId(scan: File): Int = RESULT_ID_BASE + (scan.name.hashCode() and RESULT_ID_MASK)

    companion object {
        const val ACTION_START = "com.huntercoles.splatman.processing.START"
        const val ACTION_CANCEL = "com.huntercoles.splatman.processing.CANCEL"
        const val EXTRA_SCAN = "scan"
        private const val CHANNEL_ID = "processing"
        private const val RESULT_CHANNEL_ID = "processing_result"
        private const val ONGOING_ID = 4301
        private const val RESULT_ID_BASE = 0x10000
        private const val RESULT_ID_MASK = 0xFFFF
        private const val PERCENT = 100

        /** Starts processing the capture bundle in [scan] (its folder) in the foreground. */
        fun start(context: Context, scan: File) {
            val intent = Intent(context, ProcessingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCAN, scan.path)
            context.startForegroundService(intent)
        }
    }
}
