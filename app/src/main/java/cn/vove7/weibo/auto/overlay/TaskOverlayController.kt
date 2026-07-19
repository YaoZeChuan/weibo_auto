package cn.vove7.weibo.auto.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import cn.vove7.weibo.auto.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 系统悬浮窗：关键日志 + 停止任务 + dump 节点。
 * 需要 SYSTEM_ALERT_WINDOW 权限。
 */
class TaskOverlayController(private val appContext: Context) {

    private val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var root: View? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null
    private var collectJob: Job? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = root != null

    fun canDrawOverlays(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }

    fun openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${appContext.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    fun show() {
        if (root != null) return
        if (!canDrawOverlays()) {
            Timber.w("TaskOverlay: no overlay permission")
            TaskControlHub.appendLog("⚠ 无悬浮窗权限，请先授权")
            openOverlayPermissionSettings()
            return
        }
        try {
            val view = LayoutInflater.from(appContext)
                .inflate(R.layout.overlay_task_panel, null)
            logText = view.findViewById(R.id.overlay_log_text)
            logScroll = view.findViewById(R.id.overlay_log_scroll)
            val dragBar = view.findViewById<View>(R.id.overlay_drag_bar)
            val btnStop = view.findViewById<Button>(R.id.overlay_btn_stop)
            val btnDump = view.findViewById<Button>(R.id.overlay_btn_dump)
            val btnClear = view.findViewById<Button>(R.id.overlay_btn_clear)
            val btnClose = view.findViewById<TextView>(R.id.overlay_btn_close)

            btnStop.setOnClickListener { TaskControlHub.requestStop() }
            btnDump.setOnClickListener { TaskControlHub.requestDump() }
            btnClear.setOnClickListener { TaskControlHub.requestClearLogs() }
            btnClose.setOnClickListener { hide() }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 180
            }
            layoutParams = lp

            var lastX = 0
            var lastY = 0
            var paramX = 0
            var paramY = 0
            dragBar.setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX.toInt()
                        lastY = event.rawY.toInt()
                        paramX = lp.x
                        paramY = lp.y
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX.toInt() - lastX
                        val dy = event.rawY.toInt() - lastY
                        lp.x = paramX + dx
                        lp.y = paramY + dy
                        runCatching { wm.updateViewLayout(view, lp) }
                        true
                    }
                    else -> false
                }
            }

            wm.addView(view, lp)
            root = view
            logText?.text = TaskControlHub.logText.value.ifBlank { "等待任务…" }

            collectJob?.cancel()
            collectJob = scope.launch {
                TaskControlHub.logText.collectLatest { text ->
                    logText?.text = text.ifBlank { "等待任务…" }
                    logScroll?.post {
                        logScroll?.fullScroll(View.FOCUS_DOWN)
                    }
                }
            }
            TaskControlHub.appendLog("悬浮窗已打开")
            Timber.i("TaskOverlay shown")
        } catch (e: Throwable) {
            Timber.e(e, "TaskOverlay show failed")
            TaskControlHub.appendLog("悬浮窗打开失败: ${e.message}")
            root = null
        }
    }

    fun hide() {
        collectJob?.cancel()
        collectJob = null
        val v = root ?: return
        runCatching { wm.removeView(v) }
        root = null
        logText = null
        logScroll = null
        layoutParams = null
        Timber.i("TaskOverlay hidden")
    }

    fun ensureShown() {
        if (!isShowing) show()
    }
}
