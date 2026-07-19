package cn.vove7.weibo.auto.overlay

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 任务控制中枢：关键日志、停止任务、dump 回调。
 * 悬浮窗与 ViewModel 共用。
 */
object TaskControlHub {

    private const val MAX_LINES = 120
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val lines = CopyOnWriteArrayList<String>()
    private val _logText = MutableStateFlow("")
    val logText: StateFlow<String> = _logText.asStateFlow()

    private val _taskRunning = MutableStateFlow(false)
    val taskRunning: StateFlow<Boolean> = _taskRunning.asStateFlow()

    private val stopRequested = AtomicBoolean(false)

    private val _commands = MutableSharedFlow<Command>(extraBufferCapacity = 8)
    val commands: SharedFlow<Command> = _commands.asSharedFlow()

    sealed class Command {
        data object StopTask : Command()
        data object DumpNodes : Command()
        data object ClearLogs : Command()
        data object ShowOverlay : Command()
        data object HideOverlay : Command()
    }

    fun appendLog(message: String) {
        val line = "${timeFmt.format(Date())}  $message"
        lines.add(line)
        while (lines.size > MAX_LINES) {
            lines.removeAt(0)
        }
        _logText.value = lines.joinToString("\n")
    }

    fun clearLogs() {
        lines.clear()
        _logText.value = ""
    }

    fun setTaskRunning(running: Boolean) {
        _taskRunning.value = running
        if (running) {
            stopRequested.set(false)
            appendLog("—— 任务开始 ——")
        } else {
            appendLog("—— 任务结束 ——")
        }
    }

    fun requestStop() {
        stopRequested.set(true)
        appendLog("⏹ 用户请求停止任务")
        _commands.tryEmit(Command.StopTask)
    }

    fun isStopRequested(): Boolean = stopRequested.get()

    fun clearStopRequest() {
        stopRequested.set(false)
    }

    fun requestDump() {
        appendLog("📋 用户请求 dump 节点")
        _commands.tryEmit(Command.DumpNodes)
    }

    fun requestClearLogs() {
        clearLogs()
        appendLog("日志已清除")
        _commands.tryEmit(Command.ClearLogs)
    }

    fun requestShowOverlay() {
        _commands.tryEmit(Command.ShowOverlay)
    }

    fun requestHideOverlay() {
        _commands.tryEmit(Command.HideOverlay)
    }
}
