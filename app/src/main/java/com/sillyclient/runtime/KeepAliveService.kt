package com.sillyclient.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Keeps work alive while the app is backgrounded.
 *
 * The target device freezes an app's process subtree as soon as the screen
 * goes off or the user switches away. A foreground service with a data-sync
 * type plus a partial wake lock is the platform-sanctioned exemption.
 * [KeepAlive.Reason.TASK] covers multi-minute instance work. [KeepAlive.Reason.SESSION]
 * covers a reply that is still receiving tokens, so the Node process and the
 * page can finish that reply after the screen locks or the user switches away.
 */
class KeepAliveService : Service() {
    private val wakeHandler = Handler(Looper.getMainLooper())
    private val wakeRefresh = object : Runnable {
        override fun run() {
            KeepAlive.refreshWakeLock(applicationContext)
            if (KeepAlive.hasLease()) wakeHandler.postDelayed(this, WAKE_REFRESH_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Fulfil the startForegroundService() promise as early as possible: the
        // system kills the app ("did not then call Service.startForeground()")
        // when the main thread is busy or delayed longer than the deadline, so
        // the window between start and this call must stay as short as possible.
        KeepAlive.attach(this)
        promoteToForeground()
        wakeHandler.postDelayed(wakeRefresh, WAKE_REFRESH_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KeepAlive.attach(this)
        promoteToForeground()
        KeepAlive.onServiceReady(applicationContext)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeHandler.removeCallbacks(wakeRefresh)
        KeepAlive.detach(this)
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // dataSync foreground work has a platform budget. Stop before the system
        // kills the process; a later reply can try again after this lease ends.
        KeepAlive.onPlatformTimeout()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    fun promoteToForeground() {
        try {
            val notification = buildNotification(KeepAlive.currentNotificationText())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (error: Exception) {
            // Never leave a pending foreground-service promise unfulfilled: if
            // this promotion is rejected, drop the service so release() can
            // safely start a fresh one later.
            KeepAlive.onPromotionFailed(this)
            runCatching { stopSelf() }
        }
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "实例任务", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "实例准备、迁移、清理，以及仍在接收的会话回复" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("SillyClient")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sillyclient-keepalive"
        private const val NOTIFICATION_ID = 0x5C01
        private const val WAKE_REFRESH_MS = 20L * 60 * 1000
    }
}

/**
 * Ref-counted keep-alive. Task work and an in-progress reply can hold it at
 * the same time; the service and wake lock live until the last one lets go.
 * Calls for the same [Reason] must be balanced.
 */
object KeepAlive {
    enum class Reason { TASK, SESSION }

    private val leases = linkedMapOf(Reason.TASK to 0, Reason.SESSION to 0)

    /** True once the service actually ran startForeground(); a pending start must
     *  never be stopped before that or the system kills the app. */
    private var serviceStarted = false
    private var stopRequested = false
    private var platformLimited = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var host: KeepAliveService? = null

    fun notificationText(sessionActive: Boolean): String =
        if (sessionActive) SESSION_TEXT else TASK_TEXT

    @Synchronized
    fun currentNotificationText(): String = notificationText(leases.getValue(Reason.SESSION) > 0)

    fun hasLease(): Boolean = synchronized(this) { totalLocked() > 0 }

    @Synchronized
    fun acquire(context: Context, reason: Reason = Reason.TASK) {
        val previous = leases.getValue(reason)
        leases[reason] = previous + 1
        if (totalLocked() > 1) {
            refreshPresentationLocked()
            return
        }
        stopRequested = false
        serviceStarted = false
        if (!platformLimited) {
            runCatching {
                val intent = Intent(context, KeepAliveService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
        acquireWakeLockLocked(context.applicationContext)
    }

    @Synchronized
    fun release(context: Context, reason: Reason = Reason.TASK) {
        val current = leases.getValue(reason)
        if (current == 0) return
        leases[reason] = current - 1
        if (totalLocked() > 0) {
            refreshPresentationLocked()
            return
        }
        platformLimited = false
        runCatching { wakeLock?.let { if (it.isHeld) it.release() } }
        wakeLock = null
        stopServiceWhenSafe(context)
    }

    @Synchronized
    fun refreshWakeLock(context: Context) {
        if (totalLocked() == 0) return
        acquireWakeLockLocked(context.applicationContext)
    }

    /** Called by the service right after startForeground() succeeded. */
    @Synchronized
    fun onServiceReady(context: Context) {
        serviceStarted = true
        if (stopRequested || totalLocked() == 0) stopServiceWhenSafe(context)
    }

    @Synchronized
    fun attach(service: KeepAliveService) {
        host = service
    }

    @Synchronized
    fun detach(service: KeepAliveService) {
        if (host !== service) return
        host = null
        serviceStarted = false
    }

    @Synchronized
    fun onPromotionFailed(service: KeepAliveService) {
        if (host !== service) return
        serviceStarted = false
    }

    @Synchronized
    fun onPlatformTimeout() {
        platformLimited = true
        serviceStarted = false
        stopRequested = false
        host = null
    }

    private fun refreshPresentationLocked() {
        if (!serviceStarted) return
        val service = host ?: return
        val present = Runnable {
            val current = synchronized(this@KeepAlive) { host === service && serviceStarted }
            if (current) service.promoteToForeground()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) present.run()
        else Handler(Looper.getMainLooper()).post(present)
    }

    private fun acquireWakeLockLocked(context: Context) {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SillyClient:instance-task")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_MILLIS)
                }
        }
    }

    private fun stopServiceWhenSafe(context: Context) {
        if (!serviceStarted) {
            // startForegroundService() was issued but the service has not
            // confirmed the promotion yet. Stopping now would leave the platform
            // promise unfulfilled (ForegroundServiceDidNotStartInTimeException,
            // which kills the whole app), so defer the stop instead.
            stopRequested = true
            return
        }
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
        serviceStarted = false
        stopRequested = false
    }

    private fun totalLocked(): Int = leases.values.sum()

    private const val MAX_WAKE_MILLIS = 6L * 60 * 60 * 1000
    private const val TASK_TEXT = "正在处理实例任务，请保持应用在后台运行"
    private const val SESSION_TEXT = "会话进行中，锁屏或切换应用后会继续接收并输出"
}
