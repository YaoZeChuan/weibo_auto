package cn.vove7.weibo.auto.data.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import timber.log.Timber

/** Downloads the signed release APK and hands it to Android's package installer. */
object UpdateInstaller {
    private const val PREFERENCES_NAME = "update_installer"
    private const val PENDING_APK_PATH = "pending_apk_path"
    private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
    private const val PARALLEL_DOWNLOADS = 4
    private const val USER_AGENT = "XiaomiAssistant-Android"

    suspend fun downloadAndInstall(
        context: Context,
        downloadUrl: String,
        version: String,
        expectedSha256: String?,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) {
        val apk = downloadApk(context, downloadUrl, version, onStatus, onDownloadProgress)
        try {
            verifySha256(apk, expectedSha256)
        } catch (error: Throwable) {
            apk.delete()
            throw error
        }
        withContext(Dispatchers.Main) {
            savePendingApk(context, apk)
            requestInstall(context, apk, onStatus)
        }
    }

    fun resumePendingInstall(context: Context, onStatus: (String) -> Unit) {
        val apk = pendingApk(context) ?: return
        if (!apk.exists() || apk.length() == 0L) {
            clearPendingApk(context)
            return
        }
        if (canRequestPackageInstalls(context)) {
            requestInstall(context, apk, onStatus)
        }
    }

    private suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        version: String,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(updatesDir, "xiaomai-assistant-$version.apk")
        updatesDir.listFiles()?.filter { it != apk }?.forEach { it.delete() }

        Timber.i("Update APK URL: %s", downloadUrl)
        try {
            // The accelerator redirects GitHub assets to a signed CDN URL. Downloading ranges
            // from that final URL allows four connections in parallel, like a browser download.
            val finalUrl = resolveDownloadUrl(downloadUrl)
            val totalBytes = contentLength(finalUrl)
            Timber.i("Update APK final URL: %s, bytes=%d", finalUrl, totalBytes)
            onStatus("正在加速下载 v$version…")
            onDownloadProgress(0)
            if (totalBytes > 0L) {
                try {
                    downloadInParallel(finalUrl, apk, totalBytes, version, onStatus, onDownloadProgress)
                } catch (error: Exception) {
                    Timber.w(error, "Parallel update download failed; falling back to one connection")
                    apk.delete()
                    downloadSequential(finalUrl, apk, totalBytes, version, onStatus, onDownloadProgress)
                }
            } else {
                downloadSequential(finalUrl, apk, totalBytes, version, onStatus, onDownloadProgress)
            }
            require(apk.length() > 0L) { "下载的安装包为空" }
            onDownloadProgress(100)
            apk
        } catch (error: Throwable) {
            Timber.e(error, "Update APK download failed: %s", downloadUrl)
            apk.delete()
            throw error
        }
    }

    private fun resolveDownloadUrl(url: String): String {
        var currentUrl = url
        repeat(5) {
            val connection = newConnection(currentUrl, followRedirects = false)
            try {
                val code = connection.responseCode
                val location = connection.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    // The accelerator does not accept the signed release-assets URL (HTTP 400),
                    // so download the final CDN URL directly with parallel Range requests.
                    currentUrl = URL(URL(currentUrl), location).toString()
                    Timber.i("Update APK final CDN URL: %s", currentUrl)
                } else {
                    if (code !in 200..299) error("下载服务器返回 $code")
                    return currentUrl
                }
            } finally {
                connection.disconnect()
            }
        }
        error("下载地址重定向次数过多")
    }

    private fun contentLength(url: String): Long {
        val connection = newConnection(url, followRedirects = true).apply { requestMethod = "HEAD" }
        return try {
            if (connection.responseCode !in 200..299) error("下载服务器返回 ${connection.responseCode}")
            connection.contentLengthLong
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun downloadInParallel(
        url: String,
        apk: File,
        totalBytes: Long,
        version: String,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) = coroutineScope {
        val segments = minOf(PARALLEL_DOWNLOADS.toLong(), totalBytes).toInt()
        RandomAccessFile(apk, "rw").use { it.setLength(totalBytes) }
        val downloadedBytes = AtomicLong(0)
        val lastProgress = AtomicInteger(-1)
        (0 until segments).map { index ->
            async(Dispatchers.IO) {
                val start = totalBytes * index / segments
                val end = totalBytes * (index + 1) / segments - 1
                val connection = newConnection(url, followRedirects = true).apply {
                    setRequestProperty("Range", "bytes=$start-$end")
                }
                try {
                    require(connection.responseCode == HttpURLConnection.HTTP_PARTIAL) {
                        "下载服务器不支持分段请求"
                    }
                    connection.inputStream.buffered().use { input ->
                        RandomAccessFile(apk, "rw").use { output ->
                            output.seek(start)
                            copyRange(
                                input = input,
                                output = output,
                                downloadedBytes = downloadedBytes,
                                totalBytes = totalBytes,
                                version = version,
                                lastProgress = lastProgress,
                                onStatus = onStatus,
                                onDownloadProgress = onDownloadProgress,
                            )
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }
        }.awaitAll()
    }

    private fun downloadSequential(
        url: String,
        apk: File,
        totalBytes: Long,
        version: String,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) {
        val connection = newConnection(url, followRedirects = true)
        try {
            if (connection.responseCode !in 200..299) error("下载服务器返回 ${connection.responseCode}")
            val knownLength = totalBytes.takeIf { it > 0 } ?: connection.contentLengthLong
            val downloadedBytes = AtomicLong(0)
            val lastProgress = AtomicInteger(-1)
            connection.inputStream.buffered().use { input ->
                apk.outputStream().buffered().use { output ->
                    copyRange(
                        input = input,
                        output = output,
                        downloadedBytes = downloadedBytes,
                        totalBytes = knownLength,
                        version = version,
                        lastProgress = lastProgress,
                        onStatus = onStatus,
                        onDownloadProgress = onDownloadProgress,
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun copyRange(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        downloadedBytes: AtomicLong,
        totalBytes: Long,
        version: String,
        lastProgress: AtomicInteger,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            reportProgress(
                downloadedBytes = downloadedBytes.addAndGet(count.toLong()),
                totalBytes = totalBytes,
                version = version,
                lastProgress = lastProgress,
                onStatus = onStatus,
                onDownloadProgress = onDownloadProgress,
            )
        }
    }

    private fun copyRange(
        input: java.io.InputStream,
        output: RandomAccessFile,
        downloadedBytes: AtomicLong,
        totalBytes: Long,
        version: String,
        lastProgress: AtomicInteger,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            reportProgress(
                downloadedBytes = downloadedBytes.addAndGet(count.toLong()),
                totalBytes = totalBytes,
                version = version,
                lastProgress = lastProgress,
                onStatus = onStatus,
                onDownloadProgress = onDownloadProgress,
            )
        }
    }

    private fun reportProgress(
        downloadedBytes: Long,
        totalBytes: Long,
        version: String,
        lastProgress: AtomicInteger,
        onStatus: (String) -> Unit,
        onDownloadProgress: (Int) -> Unit,
    ) {
        if (totalBytes <= 0L) return
        val progress = (downloadedBytes * 100 / totalBytes).toInt().coerceAtMost(100)
        while (true) {
            val previous = lastProgress.get()
            if (progress < previous + 5 && progress != 100) return
            if (lastProgress.compareAndSet(previous, progress)) {
                onStatus("正在下载 v$version：$progress%")
                onDownloadProgress(progress)
                return
            }
        }
    }

    private fun newConnection(url: String, followRedirects: Boolean): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun requestInstall(context: Context, apk: File, onStatus: (String) -> Unit) {
        if (!canRequestPackageInstalls(context)) {
            onStatus("请允许小麦助手安装未知应用，返回后将继续安装")
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }

        val archiveInfo = packageArchiveInfo(context.packageManager, apk)
        require(archiveInfo?.packageName == context.packageName) { "下载包与当前应用不匹配" }
        clearPendingApk(context)
        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        onStatus("下载完成，正在打开系统安装器…")
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, APK_MIME_TYPE)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun verifySha256(apk: File, expectedSha256: String?) {
        if (expectedSha256.isNullOrBlank()) return
        val actualSha256 = MessageDigest.getInstance("SHA-256").run {
            apk.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    update(buffer, 0, count)
                }
            }
            digest().joinToString("") { byte -> "%02x".format(byte) }
        }
        require(actualSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
            "安装包校验失败，请重新下载"
        }
    }

    private fun canRequestPackageInstalls(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    @Suppress("DEPRECATION")
    private fun packageArchiveInfo(packageManager: PackageManager, apk: File) =
        packageManager.getPackageArchiveInfo(apk.absolutePath, 0)

    private fun pendingApk(context: Context): File? =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(PENDING_APK_PATH, null)
            ?.let(::File)

    private fun savePendingApk(context: Context, apk: File) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PENDING_APK_PATH, apk.absolutePath)
            .apply()
    }

    private fun clearPendingApk(context: Context) {
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PENDING_APK_PATH)
            .apply()
    }
}
