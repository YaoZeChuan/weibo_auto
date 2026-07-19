package cn.vove7.weibo.auto.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import cn.vove7.weibo.auto.MainActivity
import cn.vove7.weibo.auto.R
import timber.log.Timber

/**
 * 前台保活服务，避免任务执行时进程被轻易回收。
 * 同时提供 ACTION_BRING_MAIN_TO_FRONT，任务结束后由 FGS 拉起主界面（比后台 Application 更易过 BAL）。
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    private val channelId by lazy {
        val id = "weibo_auto_keepalive"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                id,
                getString(R.string.fore_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        id
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        if (intent?.action == ACTION_BRING_MAIN_TO_FRONT) {
            Timber.i("KeepAliveService: BRING_MAIN_TO_FRONT")
            bringMainToFront()
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.fore_service_running))
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        startForeground(2001, notification)
    }

    private fun bringMainToFront() {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            }
            startActivity(intent)
            Timber.i("KeepAliveService startActivity MainActivity ok")
        } catch (e: Throwable) {
            Timber.e(e, "KeepAliveService startActivity failed")
        }
    }

    companion object {
        const val ACTION_BRING_MAIN_TO_FRONT = "cn.vove7.weibo.auto.action.BRING_MAIN_TO_FRONT"
    }
}
