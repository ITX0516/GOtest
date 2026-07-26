package com.weiqi.app.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.weiqi.app.engine.jni.NativeEngineBridge
import com.weiqi.app.remote.RemoteConfig
import com.weiqi.app.remote.RemoteEngine
import com.weiqi.app.remote.RemotePlatform
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
 * - 从 assets 解压权重文件到 filesDir/weights/
 * - 检测设备是否支持某引擎（ABI + 可用内存）
 *
 * 线程安全：内部使用 [Mutex] 串行化引擎生命周期操作。
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
     * 从 assets 复制权重文件到外部存储 `Android/data/<pkg>/files/weights/`。
     * 查找顺序：
     *  1. 标准文件名（如 `katago_b18c384.bin.gz`）→ 直接用（用户手动放或 app 抽出）
     *  2. 通配符匹配：目录下任意 `*.bin.gz`（KataGo）/ `*.txt.gz`（LeelaZero），
     *     取最近修改的一个（适用于设置里“自定义权重”导入的文件）
     *  3. assets 抽出（首次启动 / 用户未自行放置）
     *
     * @param engineType 引擎类型（KATAGO / LEELAZERO）。
     * @return 目标权重文件绝对路径。
     */
    suspend fun ensureWeightsExtracted(engineType: EngineType): String = withContext(Dispatchers.IO) {
        val (assetName, suffix) = when (engineType) {
            EngineType.KATAGO -> EnginePreferences.ASSET_KATAGO_WEIGHTS to ".bin.gz"
            EngineType.LEELAZERO -> EnginePreferences.ASSET_LEELA_WEIGHTS to ".txt.gz"
            EngineType.REMOTE -> return@withContext ""
        }
        val standardName = assetName.substringAfterLast('/')
        val destDir = preferences.getWeightsDir()
        if (!destDir.exists()) destDir.mkdirs()
        val standardFile = File(destDir, standardName)
        val isExternalDir = destDir.absolutePath.contains("/Android/data/")

        // 1. 标准文件名（外部存储）→ 直接用
        if (isExternalDir && standardFile.exists() && standardFile.length() > 1024L) {
            return@withContext standardFile.absolutePath
        }

        // 2. 通配符：扫目录下任意 *.<suffix>（用户通过设置页面自定义导入的文件）
        val customFile = destDir.listFiles { f ->
            f.isFile && f.name.endsWith(suffix, ignoreCase = true) && f.length() > 1024L
        }?.maxByOrNull { it.lastModified() }
        if (customFile != null) {
            return@withContext customFile.absolutePath
        }

        // 3. 从 assets 解压（首次启动 / 用户未自行放置）
        if (!standardFile.exists() || standardFile.length() == 0L) {
            try {
                appContext.assets.open(assetName).use { input ->
                    standardFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
                throw EngineException(
                    "权重文件未找到：assets/$assetName。" +
                        "请将 ${engineType.displayName} 权重 (后缀 ${suffix}) 放到 ${destDir.absolutePath}/ 目录下，" +
                        "或在设置中使用“自定义权重”按钮选择文件。",
                    e
                )
            }
        }
        standardFile.absolutePath
    }

    /**
     * 从 assets 复制引擎可执行文件到外部存储 `Android/data/<pkg>/files/bin/`，
     * 已存在则跳过，并设置可执行权限。
     * PROCESS 模式（子进程方式）需要引擎可执行文件（katago / leelaz）。
     *
     * @param engineType 引擎类型（KATAGO / LEELAZERO）。
     * @return 目标可执行文件绝对路径；失败时抛出 [EngineException]。
     */
    suspend fun ensureBinaryExtracted(engineType: EngineType): String = withContext(Dispatchers.IO) {
        val (assetName, binaryName) = when (engineType) {
            EngineType.KATAGO -> EnginePreferences.ASSET_KATAGO_BINARY to "katago"
            EngineType.LEELAZERO -> EnginePreferences.ASSET_LEELA_BINARY to "leelaz"
            EngineType.REMOTE -> return@withContext ""
        }
        val destDir = appContext.getExternalFilesDir("bin")
            ?: File(appContext.filesDir, "bin")
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, binaryName)

        if (!destFile.exists() || destFile.length() == 0L) {
            try {
                appContext.assets.open(assetName).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                destFile.setExecutable(true, false)
            } catch (e: IOException) {
                throw EngineException(
                    "引擎可执行文件未找到：assets/$assetName。" +
                        "请编译 ${engineType.displayName} 并放到 app/src/main/assets/bin/ 下。",
                    e
                )
            }
        } else {
            if (!destFile.canExecute()) {
                destFile.setExecutable(true, false)
            }
        }
        destFile.absolutePath
    }

    /**
     * 从 assets 复制引擎配置文件到外部存储 `Android/data/<pkg>/files/config/`，
     * 已存在则跳过。
     * KataGo 支持可选的 .cfg 配置文件。
     *
     * @param assetName 配置文件在 assets 中的路径。
     * @return 目标配置文件绝对路径；文件不存在则返回空字符串。
     */
    suspend fun ensureConfigExtracted(assetName: String): String = withContext(Dispatchers.IO) {
        if (assetName.isBlank()) return@withContext ""
        val destDir = appContext.getExternalFilesDir("config")
            ?: File(appContext.filesDir, "config")
        if (!destDir.exists()) destDir.mkdirs()
        val destFile = File(destDir, assetName.substringAfterLast('/'))

        if (!destFile.exists() || destFile.length() == 0L) {
            try {
                appContext.assets.open(assetName).use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: IOException) {
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
        if (!deviceSupportsEngine(type)) {
            throw EngineException("设备不支持 ${type.displayName} 引擎（需 arm64-v8a/x86_64 且可用内存 ≥ 1GB）")
        }
        val engine: GoEngine = when (type) {
            EngineType.KATAGO -> {
                val weights = try { ensureWeightsExtracted(type) } catch (e: EngineException) { "" }
                val binaryPath = try { ensureBinaryExtracted(type) } catch (e: EngineException) { "" }
                val configPath = try { ensureConfigExtracted(EnginePreferences.ASSET_KATAGO_CONFIG) } catch (_: Exception) { "" }
                val workingDir = (appContext.getExternalFilesDir("bin")
                    ?: File(appContext.filesDir, "bin")).absolutePath
                val cfg = preferences.getKataGoConfig().copy(
                    weightsPath = weights,
                    executablePath = binaryPath,
                    configPath = configPath,
                    workingDir = workingDir
                )
                KataGoEngine(cfg)
            }
            EngineType.LEELAZERO -> {
                val weights = try { ensureWeightsExtracted(type) } catch (e: EngineException) { "" }
                val binaryPath = try { ensureBinaryExtracted(type) } catch (e: EngineException) { "" }
                val workingDir = (appContext.getExternalFilesDir("bin")
                    ?: File(appContext.filesDir, "bin")).absolutePath
                val cfg = preferences.getLeelaZeroConfig().copy(
                    weightsPath = weights,
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
            engine.start()
        } catch (e: EngineException) {
            throw e
        } catch (e: UnsatisfiedLinkError) {
            throw EngineException("无法加载 libweiqi_engine.so：${e.message}", e)
        } catch (e: Throwable) {
            throw EngineException("启动 ${type.displayName} 引擎失败：${e.message}", e)
        }
        return engine
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
