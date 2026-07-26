package com.weiqi.app.engine

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * KataGo 官方权重下载信息。
 */
data class WeightInfo(
    val key: String,
    val label: String,
    val description: String,
    val url: String,
    val fileName: String,
    val sizeMB: Int
) {
    companion object {
        val B10 = WeightInfo(
            key = "b10",
            label = "b10c128（快速）",
            description = "业余 1-3 段 | 手机 1-2秒/步",
            url = "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b10c128-s1141046784-d204142634.bin.gz",
            fileName = "katago_b10c128.bin.gz",
            sizeMB = 12
        )
        val B18 = WeightInfo(
            key = "b18",
            label = "b18c384（平衡）",
            description = "业余 5-6 段 | 手机 5-10秒/步",
            url = "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b18c384nbt-s9996604416-d4316597426.bin.gz",
            fileName = "katago_b18c384nbt.bin.gz",
            sizeMB = 93
        )
        val B28 = WeightInfo(
            key = "b28",
            label = "b28c512（最强）",
            description = "职业水平 | 手机 30+秒/步",
            url = "https://media.katagotraining.org/uploaded/networks/models/kata1/kata1-b28c512nbt-s12283775232-d5679728027.bin.gz",
            fileName = "katago_b28c512nbt.bin.gz",
            sizeMB = 259
        )

        val ALL = listOf(B10, B18, B28)
    }
}

/**
 * 权重下载管理器。
 *
 * 使用 Android 系统 [DownloadManager] 下载 KataGo 权重文件到
 * `/sdcard/Download/` 目录。下载进度由系统通知栏显示，
 * 完成后自动触发 [onDownloadComplete] 回调。
 */
class WeightDownloadManager(private val context: Context) {

    private val appContext: Context = context.applicationContext
    private val downloadManager: DownloadManager =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 开始下载指定权重文件。
     * @return DownloadManager 的 download ID，可用于查询进度。
     */
    fun startDownload(weight: WeightInfo): Long {
        val request = DownloadManager.Request(Uri.parse(weight.url)).apply {
            setTitle("下载 KataGo 权重: ${weight.label}")
            setDescription("${weight.fileName} (${weight.sizeMB}MB)")
            setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                weight.fileName
            )
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val downloadId = downloadManager.enqueue(request)
        prefs.edit().putLong("${KEY_DOWNLOAD_PREFIX}${weight.key}", downloadId).apply()
        return downloadId
    }

    /**
     * 查询下载状态。返回 [DownloadStatus]。
     */
    fun queryStatus(downloadId: Long): DownloadStatus {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor: Cursor = downloadManager.query(query) ?: return DownloadStatus.NotFound
        return cursor.use {
            if (!it.moveToFirst()) return@use DownloadStatus.NotFound
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val bytesDownloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val bytesTotal = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> DownloadStatus.Completed
                DownloadManager.STATUS_FAILED -> DownloadStatus.Failed(
                    it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                )
                DownloadManager.STATUS_PAUSED -> DownloadStatus.Paused(bytesDownloaded, bytesTotal)
                DownloadManager.STATUS_PENDING -> DownloadStatus.Pending
                DownloadManager.STATUS_RUNNING -> DownloadStatus.Downloading(bytesDownloaded, bytesTotal)
                else -> DownloadStatus.Unknown
            }
        }
    }

    /**
     * 获取最后已知的下载 ID。
     */
    fun getLastDownloadId(weightKey: String): Long =
        prefs.getLong("${KEY_DOWNLOAD_PREFIX}$weightKey", -1L)

    /**
     * 监听下载完成事件的 Flow。
     */
    fun downloadCompleteFlow(): Flow<Long> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id >= 0) trySend(id)
                }
            }
        }
        appContext.registerReceiver(
            receiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            Context.RECEIVER_NOT_EXPORTED
        )
        awaitClose { appContext.unregisterReceiver(receiver) }
    }

    companion object {
        private const val PREFS_NAME = "weiqi_download_prefs"
        private const val KEY_DOWNLOAD_PREFIX = "download_id_"
    }
}

/**
 * 下载状态。
 */
sealed class DownloadStatus {
    data object NotFound : DownloadStatus()
    data object Pending : DownloadStatus()
    data object Completed : DownloadStatus()
    data class Downloading(val bytesDownloaded: Long, val bytesTotal: Long) : DownloadStatus()
    data class Paused(val bytesDownloaded: Long, val bytesTotal: Long) : DownloadStatus()
    data class Failed(val reason: Int) : DownloadStatus()
    data object Unknown : DownloadStatus()

    val progressPercent: Int
        get() = when (this) {
            is Downloading -> if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0
            is Paused -> if (bytesTotal > 0) ((bytesDownloaded * 100) / bytesTotal).toInt() else 0
            is Completed -> 100
            else -> 0
        }
}
