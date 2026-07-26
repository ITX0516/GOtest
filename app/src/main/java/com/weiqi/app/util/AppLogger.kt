package com.weiqi.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 应用全局文件日志器。
 *
 * 写入位置：`Android/data/com.weiqi.app/files/logs/app.log`
 * （即 `/sdcard/Android/data/com.weiqi.app/files/logs/app.log`，
 *  scoped storage 下用户可用文件管理器直接查看）
 *
 * 特性：
 * - 单线程顺序写入，避免多线程交错
 * - 自动轮转：超过 [MAX_LOG_SIZE] 时滚动为 app.log.old
 * - Kotlin / C++ 双端共用（C++ 通过 [nativeWriteLog] JNI 回调进来）
 * - 同时输出到 logcat 便于 adb 实时查看
 *
 * 使用方式：
 *   AppLogger.init(context)            // 在 Application.onCreate 调一次
 *   AppLogger.i("tag", "message")      // 写日志
 *   AppLogger.e("tag", "msg", throwable)
 */
object AppLogger {

    private const val MAX_LOG_SIZE = 2L * 1024 * 1024  // 2MB
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "app.log"
    private const val LOG_FILE_OLD = "app.log.old"

    private val executor = Executors.newSingleThreadExecutor()
    private val counter = AtomicLong(0)

    @Volatile private var logFile: File? = null
    @Volatile private var writer: java.io.BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** 初始化日志文件；应在 Application.onCreate 中调用。 */
    fun init(context: Context) {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, LOG_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, LOG_FILE)
        logFile = file
        try {
            // 滚动旧日志
            if (file.exists() && file.length() > MAX_LOG_SIZE) {
                val old = File(dir, LOG_FILE_OLD)
                if (old.exists()) old.delete()
                file.renameTo(old)
            }
            writer = file.bufferedWriter(Charsets.UTF_8, 8192)
            // 启动横幅
            internalWrite("=".repeat(60))
            internalWrite("WeiqiApp 启动 @ ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
            internalWrite("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            internalWrite("Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
            internalWrite("ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
            internalWrite("nativeLibraryDir: ${context.applicationInfo.nativeLibraryDir}")
            internalWrite("=".repeat(60))
        } catch (e: Exception) {
            Log.e("AppLogger", "初始化日志失败", e)
        }
    }

    fun v(tag: String, msg: String) = write("V", tag, msg, null)
    fun d(tag: String, msg: String) = write("D", tag, msg, null)
    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)

    /** 返回当前日志文件，便于设置页分享/查看。 */
    fun getLogFile(): File? = logFile

    /** 清空日志文件。 */
    fun clear() {
        executor.execute {
            try {
                writer?.flush()
                logFile?.writeText("")
                counter.set(0)
            } catch (_: Exception) {}
        }
    }

    /**
     * 由 C++ 通过 JNI 调用，把 native 日志写入同一文件。
     * 必须为 @JvmStatic 以便 JNI 查找静态方法。
     */
    @JvmStatic
    fun nativeWriteLog(level: String, tag: String, msg: String) {
        write(level, tag, msg, null)
    }

    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val ts = dateFormat.format(Date())
        val line = if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            "$ts $level/$tag: $msg\n$sw"
        } else {
            "$ts $level/$tag: $msg"
        }
        // 同时输出到 logcat
        when (level) {
            "V" -> Log.v(tag, msg)
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
            "E" -> if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
        internalWrite(line)
    }

    private fun internalWrite(line: String) {
        executor.execute {
            try {
                val w = writer ?: return@execute
                w.write(line)
                w.newLine()
                w.flush()
                // 大小检查，超限滚动
                if (counter.incrementAndGet() % 500 == 0L) {
                    val f = logFile
                    if (f != null && f.length() > MAX_LOG_SIZE) {
                        w.flush()
                        val old = File(f.parentFile, LOG_FILE_OLD)
                        if (old.exists()) old.delete()
                        f.renameTo(old)
                        // 重新打开
                        logFile?.let { nf ->
                            writer = nf.bufferedWriter(Charsets.UTF_8, 8192)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
