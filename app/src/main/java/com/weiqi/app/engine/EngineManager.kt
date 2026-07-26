package com.weiqi.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.weiqi.app.engine.jni.NativeEngineBridge
import com.weiqi.app.remote.RemoteConfig
import com.weiqi.app.remote.RemoteEngine
import com.weiqi.app.remote.RemotePlatform
import com.weiqi.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 引擎生命周期管理器。
 *
 * 负责：
 * - 根据用户偏好创建/启动/切换/停止 [GoEngine]
 * - 从 assets 解压权重/二进制文件（仅作为备选，主要靠用户自定义路径）
 * - 自动扫描设备公共目录（Download / WeiqiApp/）查找权重与引擎文件
 * - 检测设备是否支持某引擎（ABI + 可用内存）
 *
 * 线程安全：内部使用 [Mutex] 串行化引擎生命周期操作。
 *
 * 文件路径优先级（从高到低）：
 * 1. 用户在设置页自定义的路径
 * 2. 从 assets 解压到 filesDir 的副本
 * 3. 自动扫描公共目录（/sdcard/Download/、/sdcard/WeiqiApp/weights/ 等）
 *
 * @param context 应用上下文。
 * @param preferences 引擎偏好设置。
 */
class EngineManager(
    private val context: Context,
    private val preferences: EnginePreferences
) {
    private val appContext: Context = context.applicationContext
    private val lifecycleMutex = Mutex()

    private companion object {
        const val TAG = "EngineManager"
    }

    @Volatile private var currentEngine: GoEngine? = null

    /** 当前活跃引擎；未启动时为 null。 */
    val activeEngine: GoEngine? get() = currentEngine

    /** 引擎是否正在运行（已 start 且未 shutdown）。 */
    val isEngineRunning: Boolean
        get() = currentEngine?.isReady == true

    /**
     * 启动当前偏好中选定的引擎。若已启动且类型一致，直接返回。
     * @return 已启动的引擎。
     * @throws EngineException 启动失败（设备不支持、权重缺失、native 加载失败等）。
     */
    suspend fun startEngine(): GoEngine = lifecycleMutex.withLock {
        val type = preferences.getCurrentEngineType()
        val existing = currentEngine
        if (existing != null && existing.type == type && existing.isReady) {
            return@withLock existing
        }
        // 类型不一致或未就绪，先停止旧的
        existing?.let { stopInternal(it) }
        val engine = createAndStart(type)
        currentEngine = engine
        engine
    }

    /**
     * 切换到指定类型的引擎。会先停止当前引擎。
     * @param type 目标引擎类型。
     * @return 新启动的引擎。
     */
    suspend fun switchEngine(type: EngineType): GoEngine = lifecycleMutex.withLock {
        currentEngine?.let { stopInternal(it) }
        preferences.setCurrentEngineType(type)
        val engine = createAndStart(type)
        currentEngine = engine
        engine
    }

    /** 停止当前引擎并释放资源。 */
    suspend fun stopEngine() = lifecycleMutex.withLock {
        currentEngine?.let { stopInternal(it) }
        currentEngine = null
    }

    /**
     * 从 assets 复制权重文件到 `filesDir/weights/`，已存在则跳过。
     * 仅作为备选方案；推荐用户通过设置页自定义路径。
     *
     * @param engineType 引擎类型（KATAGO / LEELAZERO）。
     * @return 目标权重文件绝对路径；assets 中无对应文件则返回空字符串（不抛异常）。
     */
    suspend fun ensureWeightsExtracted(engineType: EngineType): String = withContext(Dispatchers.IO) {
        val assetName = when (engineType) {
            EngineType.KATAGO -> EnginePreferences.ASSET_KATAGO_WEIGHTS
            EngineType.LEELAZERO -> EnginePreferences.ASSET_LEELA_WEIGHTS
            EngineType.REMOTE -> return@withContext ""
        }
        val destDir = preferences.getWeightsDir()
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, assetName.substringAfterLast('/'))

        if (!destFile.exists() || destFile.length() == 0L) {
            try {
                appContext.assets.open(assetName).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: IOException) {
                return@withContext ""
            }
        }
        destFile.absolutePath
    }

    /**
     * 返回引擎可执行文件路径（从 jniLibs 打包，位于 nativeLibraryDir）。
     *
     * Android 10+ 的 W^X 策略禁止从 `filesDir` 执行二进制，
     * 因此引擎二进制通过 jniLibs 打包为 `lib*.so`，安装时由 PackageManager
     * 解压到 `nativeLibraryDir`，这是唯一可靠的可执行目录。
     *
     * LeelaZero 不随 APK 打包，此方法返回空字符串，用户需在设置页自选二进制。
     *
     * @param engineType 引擎类型（KATAGO / LEELAZERO）。
     * @return 可执行文件绝对路径；未打包则返回空字符串。
     */
    fun ensureBinaryExtracted(engineType: EngineType): String {
        if (engineType == EngineType.REMOTE) return ""

        val soName = when (engineType) {
            EngineType.KATAGO -> "libkatago.so"
            EngineType.LEELAZERO -> "libleelaz.so"
            EngineType.REMOTE -> return ""
        }

        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeDir, soName)
        return if (nativeFile.exists() && nativeFile.canExecute()) {
            nativeFile.absolutePath
        } else {
            ""
        }
    }

    /** 从 assets 复制单个文件到目标目录。已存在且大小相同则跳过。 */
    private fun copyAssetToDir(assetName: String, destDirPath: String, destFileName: String): Boolean {
        return try {
            val destDir = File(destDirPath)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, destFileName)
            appContext.assets.open(assetName).use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            true
        } catch (_: IOException) {
            false
        }
    }

    /**
     * 生成最小 KataGo 配置文件（如 assets 中无配置文件）。
     * 关键设置：禁用 logToStdout（避免干扰 GTP 协议）、禁用日志文件。
     */
    private fun ensureDefaultKatagoConfig(): String = try {
        val configDir = File(appContext.filesDir, "config")
        if (!configDir.exists()) configDir.mkdirs()
        val configFile = File(configDir, "katago_default.cfg")
        if (!configFile.exists()) {
            configFile.writeText(DEFAULT_KATAGO_CONFIG)
        }
        configFile.absolutePath
    } catch (_: Exception) {
        ""
    }

    /**
     * 从 assets 复制引擎配置文件到 `filesDir/config/`，已存在则跳过。
     *
     * @param assetName 配置文件在 assets 中的路径。
     * @return 目标配置文件绝对路径；文件不存在则返回空字符串。
     */
    suspend fun ensureConfigExtracted(assetName: String): String = withContext(Dispatchers.IO) {
        if (assetName.isBlank()) return@withContext ""
        val destDir = File(appContext.filesDir, "config")
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, assetName.substringAfterLast('/'))

        if (!destFile.exists() || destFile.length() == 0L) {
            try {
                appContext.assets.open(assetName).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: IOException) {
                return@withContext ""
            }
        }
        destFile.absolutePath
    }

    /**
     * 检测当前设备是否支持指定引擎。
     * 判据：ABI 为 arm64-v8a 或 x86_64，且可用内存 ≥ 1GB。
     * REMOTE 引擎始终返回 true。
     */
    fun deviceSupportsEngine(type: EngineType): Boolean {
        if (type == EngineType.REMOTE) return true
        val abis = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Build.SUPPORTED_ABIS.toList()
        } else {
            listOf(Build.CPU_ABI, Build.CPU_ABI2)
        }
        val abiOk = abis.any { it == "arm64-v8a" || it == "x86_64" }
        if (!abiOk) return false

        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return false
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem >= MIN_REQUIRED_MEMORY_BYTES
    }

    // ===== 内部实现 =====

    private suspend fun createAndStart(type: EngineType): GoEngine {
        AppLogger.i(TAG, "createAndStart: type=$type")
        if (!deviceSupportsEngine(type)) {
            AppLogger.e(TAG, "设备不支持 $type 引擎（ABI 或内存检查未通过）")
            throw EngineException("设备不支持 ${type.displayName} 引擎（需 arm64-v8a/x86_64 且可用内存 ≥ 1GB）")
        }
        val engine: GoEngine = when (type) {
            EngineType.KATAGO -> {
                ensureWeightsExtracted(type)
                val extractedBinary = ensureBinaryExtracted(type)
                val weightsPath = preferences.resolveKataGoWeightsPath()
                // 二进制路径优先级：ensureBinaryExtracted > 用户自定义 > 公共目录扫描
                val binaryPath = extractedBinary.ifBlank { preferences.resolveKataGoBinaryPath() }
                AppLogger.i(TAG, "KataGo 路径解析: weights=$weightsPath  binary=$binaryPath")
                // 预检查：权重与二进制必须存在，否则给出明确错误（避免 native crash 闪退）
                requireFile(weightsPath, "KataGo 权重文件", "请在设置页选择 .bin.gz 权重文件")
                requireExecutable(binaryPath, "KataGo 引擎二进制",
                    "未找到 katago 二进制。请在设置页选择引擎二进制文件（可从阿Q围棋等 app 提取 libkatago.so）")
                val configPath = preferences.getKataGoConfigPath().ifBlank {
                    try { ensureConfigExtracted(EnginePreferences.ASSET_KATAGO_CONFIG) } catch (_: Exception) { "" }
                }.ifBlank { ensureDefaultKatagoConfig() }
                val workingDir = appContext.filesDir.absolutePath
                AppLogger.i(TAG, "KataGo 配置: config=$configPath  workingDir=$workingDir")
                val cfg = preferences.getKataGoConfig().copy(
                    weightsPath = weightsPath,
                    executablePath = binaryPath,
                    configPath = configPath,
                    workingDir = workingDir
                )
                KataGoEngine(cfg)
            }
            EngineType.LEELAZERO -> {
                ensureWeightsExtracted(type)
                val extractedBinary = ensureBinaryExtracted(type)
                val weightsPath = preferences.resolveLeelaWeightsPath()
                val binaryPath = extractedBinary.ifBlank { preferences.resolveLeelaBinaryPath() }
                AppLogger.i(TAG, "LeelaZero 路径解析: weights=$weightsPath  binary=$binaryPath")
                // 预检查：LeelaZero 二进制不会随 APK 打包，必须由用户提供
                requireFile(weightsPath, "LeelaZero 权重文件", "请在设置页选择 .txt.gz 权重文件")
                requireExecutable(binaryPath, "LeelaZero 引擎二进制",
                    "未找到 leelaz 二进制。LeelaZero 不会随 APK 打包，请在设置页选择引擎二进制文件（可从阿Q围棋等 app 提取 libleelaz.so）")
                val workingDir = appContext.filesDir.absolutePath
                val cfg = preferences.getLeelaZeroConfig().copy(
                    weightsPath = weightsPath,
                    executablePath = binaryPath,
                    workingDir = workingDir
                )
                LeelaZeroEngine(cfg)
            }
            EngineType.REMOTE -> {
                val cfg = preferences.getRemoteConfig()
                RemoteEngine(cfg.toRemoteConfig())
            }
        }

        try {
            AppLogger.i(TAG, "调用 engine.start()...")
            engine.start()
            AppLogger.i(TAG, "engine.start() 成功")
        } catch (e: EngineException) {
            AppLogger.e(TAG, "engine.start() 抛 EngineException: ${e.message}", e)
            throw e
        } catch (e: UnsatisfiedLinkError) {
            AppLogger.e(TAG, "engine.start() 抛 UnsatisfiedLinkError: ${e.message}", e)
            throw EngineException("无法加载 libweiqi_engine.so：${e.message}", e)
        } catch (e: Throwable) {
            AppLogger.e(TAG, "engine.start() 抛异常: ${e.javaClass.simpleName}: ${e.message}", e)
            throw EngineException("启动 ${type.displayName} 引擎失败：${e.message}", e)
        }
        return engine
    }

    /** 检查普通文件是否存在且可读；不存在抛出带提示的 [EngineException]。 */
    private fun requireFile(path: String, label: String, hint: String) {
        if (path.isBlank()) {
            AppLogger.e(TAG, "requireFile 失败：$label 路径为空。$hint")
            throw EngineException("$label 未设置。$hint")
        }
        val f = File(path)
        if (!f.exists() || !f.canRead()) {
            AppLogger.e(TAG, "requireFile 失败：$label 不存在或不可读: $path。$hint")
            throw EngineException("$label 不存在或不可读：$path。$hint")
        }
        if (f.length() == 0L) {
            AppLogger.e(TAG, "requireFile 失败：$label 为空文件: $path。$hint")
            throw EngineException("$label 为空文件：$path。$hint")
        }
        AppLogger.i(TAG, "requireFile 通过: $label size=${f.length()} path=$path")
    }

    /** 检查可执行文件是否存在且可执行；不存在抛出带提示的 [EngineException]。 */
    private fun requireExecutable(path: String, label: String, hint: String) {
        if (path.isBlank()) {
            AppLogger.e(TAG, "requireExecutable 失败：$label 路径为空。$hint")
            throw EngineException("$label 未设置。$hint")
        }
        val f = File(path)
        if (!f.exists()) {
            AppLogger.e(TAG, "requireExecutable 失败：$label 不存在: $path。$hint")
            throw EngineException("$label 不存在：$path。$hint")
        }
        if (!f.canExecute()) {
            AppLogger.e(TAG, "requireExecutable 失败：$label 不可执行: $path（W^X 拦截？应放 nativeLibraryDir）。$hint")
            throw EngineException("$label 不可执行：$path。$hint")
        }
        AppLogger.i(TAG, "requireExecutable 通过: $label size=${f.length()} path=$path")
    }

    private suspend fun stopInternal(engine: GoEngine) {
        try {
            engine.shutdown()
        } catch (_: Throwable) {
            // 关闭异常不应阻塞后续操作
        }
    }

    companion object {
        /** 引擎最低可用内存要求：1GB。 */
        private const val MIN_REQUIRED_MEMORY_BYTES = 1L * 1024 * 1024 * 1024

        /**
         * KataGo 最小配置（手机端 GTP 模式）。
         * 关键：禁用 logToStdout（否则日志会干扰 GTP 协议解析）。
         */
        private const val DEFAULT_KATAGO_CONFIG = """
# KataGo 手机端最小配置
# 日志
logDir = .
logDirGTP = .
logToStdout = false
logAllGTPCommunication = false
logSearchInfo = false
# 搜索
numSearchThreads = 2
maxVisits = 800
# 棋盘
defaultBoardSize = 19
defaultKomi = 7.5
maxBoardSize = 19
# 神经网络（CPU 模式）
nnMaxBatchSize = 1
"""

        /** 探测 native 端某引擎类型是否已链接（不依赖 EnginePreferences）。 */
        fun isNativeEngineAvailable(engineType: EngineType): Boolean {
            if (engineType == EngineType.REMOTE) return false
            return try {
                NativeEngineBridge.isEngineAvailable(
                    when (engineType) {
                        EngineType.KATAGO -> NativeEngineBridge.ENGINE_KATAGO
                        EngineType.LEELAZERO -> NativeEngineBridge.ENGINE_LEELAZERO
                        else -> return false
                    }
                )
            } catch (_: Throwable) {
                false
            }
        }
    }
}
