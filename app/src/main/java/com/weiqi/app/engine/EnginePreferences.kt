package com.weiqi.app.engine

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * 引擎偏好设置持久化。
 *
 * 基于 SharedPreferences 存储：
 * - 当前选中引擎类型
 * - 用户自定义权重/引擎二进制/配置文件路径
 * - KataGo / LeelaZero / 远程引擎的 [EngineConfig]
 *
 * 权重与引擎文件由用户自行下载（浏览器下载），放到设备存储后，
 * 在设置页通过文件选择器指定路径。
 *
 * 公共扫描目录（引擎启动时自动查找）：
 * - /sdcard/Download/         浏览器默认下载目录
 * - /sdcard/WeiqiApp/weights/  推荐权重存放目录
 * - /sdcard/WeiqiApp/bin/      推荐引擎可执行文件目录
 *
 * @param context 应用上下文。
 */
class EnginePreferences(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 获取当前选中的引擎类型，默认 [EngineType.KATAGO]。 */
    fun getCurrentEngineType(): EngineType {
        val name = prefs.getString(KEY_CURRENT_ENGINE, EngineType.KATAGO.name) ?: EngineType.KATAGO.name
        return runCatching { EngineType.valueOf(name) }.getOrDefault(EngineType.KATAGO)
    }

    /** 设置当前选中的引擎类型。 */
    fun setCurrentEngineType(type: EngineType) {
        prefs.edit().putString(KEY_CURRENT_ENGINE, type.name).apply()
    }

    // ===== 用户自定义文件路径 =====

    /** 获取用户设置的 KataGo 权重文件路径。 */
    fun getKataGoWeightsPath(): String =
        prefs.getString(KEY_KATAGO_WEIGHTS_PATH, "") ?: ""

    /** 设置用户选择的 KataGo 权重文件路径。 */
    fun setKataGoWeightsPath(path: String) {
        prefs.edit().putString(KEY_KATAGO_WEIGHTS_PATH, path).apply()
    }

    /** 获取用户设置的 KataGo 引擎可执行文件路径（PROCESS 模式用）。 */
    fun getKataGoBinaryPath(): String =
        prefs.getString(KEY_KATAGO_BINARY_PATH, "") ?: ""

    /** 设置用户选择的 KataGo 引擎可执行文件路径。 */
    fun setKataGoBinaryPath(path: String) {
        prefs.edit().putString(KEY_KATAGO_BINARY_PATH, path).apply()
    }

    /** 获取用户设置的 KataGo 配置文件路径（可选）。 */
    fun getKataGoConfigPath(): String =
        prefs.getString(KEY_KATAGO_CONFIG_PATH, "") ?: ""

    /** 设置用户选择的 KataGo 配置文件路径。 */
    fun setKataGoConfigPath(path: String) {
        prefs.edit().putString(KEY_KATAGO_CONFIG_PATH, path).apply()
    }

    /** 获取用户设置的 LeelaZero 权重文件路径。 */
    fun getLeelaWeightsPath(): String =
        prefs.getString(KEY_LEELA_WEIGHTS_PATH, "") ?: ""

    /** 设置用户选择的 LeelaZero 权重文件路径。 */
    fun setLeelaWeightsPath(path: String) {
        prefs.edit().putString(KEY_LEELA_WEIGHTS_PATH, path).apply()
    }

    /** 获取用户设置的 LeelaZero 引擎可执行文件路径（PROCESS 模式用）。 */
    fun getLeelaBinaryPath(): String =
        prefs.getString(KEY_LEELA_BINARY_PATH, "") ?: ""

    /** 设置用户选择的 LeelaZero 引擎可执行文件路径。 */
    fun setLeelaBinaryPath(path: String) {
        prefs.edit().putString(KEY_LEELA_BINARY_PATH, path).apply()
    }

    /** 获取 KataGo 配置；未设置时返回默认配置。 */
    fun getKataGoConfig(): EngineConfig {
        val json = prefs.getString(KEY_KATAGO_CONFIG, null) ?: return defaultKataGoConfig()
        return runCatching { EngineConfig.fromJson(json) }.getOrDefault(defaultKataGoConfig())
    }

    /** 设置 KataGo 配置。 */
    fun setKataGoConfig(config: EngineConfig) {
        require(config.type == EngineType.KATAGO) { "EngineConfig.type 必须为 KATAGO" }
        prefs.edit().putString(KEY_KATAGO_CONFIG, config.toJson()).apply()
    }

    /** 获取 LeelaZero 配置；未设置时返回默认配置。 */
    fun getLeelaZeroConfig(): EngineConfig {
        val json = prefs.getString(KEY_LEELA_CONFIG, null) ?: return defaultLeelaConfig()
        return runCatching { EngineConfig.fromJson(json) }.getOrDefault(defaultLeelaConfig())
    }

    /** 设置 LeelaZero 配置。 */
    fun setLeelaZeroConfig(config: EngineConfig) {
        require(config.type == EngineType.LEELAZERO) { "EngineConfig.type 必须为 LEELAZERO" }
        prefs.edit().putString(KEY_LEELA_CONFIG, config.toJson()).apply()
    }

    /** 获取远程引擎配置；未设置时返回默认配置。 */
    fun getRemoteConfig(): EngineConfig {
        val json = prefs.getString(KEY_REMOTE_CONFIG, null) ?: return defaultRemoteConfig()
        return runCatching { EngineConfig.fromJson(json) }.getOrDefault(defaultRemoteConfig())
    }

    /** 设置远程引擎配置。 */
    fun setRemoteConfig(config: EngineConfig) {
        require(config.type == EngineType.REMOTE) { "EngineConfig.type 必须为 REMOTE" }
        prefs.edit().putString(KEY_REMOTE_CONFIG, config.toJson()).apply()
    }

    /**
     * 获取权重文件目录（`filesDir/weights/`），不存在则创建。
     */
    fun getWeightsDir(): File {
        val dir = File(appContext.filesDir, "weights")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取引擎二进制目录（`filesDir/bin/`），不存在则创建。
     */
    fun getBinDir(): File {
        val dir = File(appContext.filesDir, "bin")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * 获取推荐权重存放目录（/sdcard/WeiqiApp/weights/）。
     */
    fun getRecommendedWeightsDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "WeiqiApp/weights")
        return dir
    }

    /**
     * 推荐引擎可执行文件存放目录（/sdcard/WeiqiApp/bin/）。
     */
    fun getRecommendedBinDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "WeiqiApp/bin")
        return dir
    }

    // ===== 解析最佳可用路径 =====

    /**
     * 解析 KataGo 权重文件的最终路径，按优先级：
     * 1. 用户自定义路径（设置页选择的）
     * 2. 从 assets 解压到 filesDir/weights/ 的
     * 3. 扫描公共目录（Download / WeiqiApp/weights/）
     * 4. 都不存在则返回空字符串
     */
    fun resolveKataGoWeightsPath(): String {
        // 1. 用户自定义路径
        val custom = getKataGoWeightsPath()
        if (custom.isNotBlank() && File(custom).exists() && File(custom).canRead()) {
            return custom
        }

        // 2. assets 解压路径
        val extracted = File(getWeightsDir(), ASSET_KATAGO_WEIGHTS.substringAfterLast('/'))
        if (extracted.exists() && extracted.length() > 0) {
            return extracted.absolutePath
        }

        // 3. 扫描公共目录
        val found = findWeightFile(KATAGO_WEIGHT_PATTERNS)
        return found?.absolutePath ?: ""
    }

    /**
     * 解析 KataGo 引擎可执行文件的最终路径，按优先级：
     * 1. 用户自定义路径
     * 2. nativeLibraryDir/libkatago.so（jniLibs 打包，Android 10+ W^X 策略下唯一可靠）
     * 3. 从 assets 解压到 filesDir/bin/ 的（Android 9 及以下回退）
     * 4. 扫描公共目录
     * 5. 都不存在则返回空字符串
     */
    fun resolveKataGoBinaryPath(): String {
        val custom = getKataGoBinaryPath()
        if (custom.isNotBlank() && File(custom).exists() && File(custom).canExecute()) {
            return custom
        }

        // nativeLibraryDir（jniLibs 打包的 libkatago.so）
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeDir, "libkatago.so")
        if (nativeFile.exists() && nativeFile.canExecute()) {
            return nativeFile.absolutePath
        }

        val extracted = File(getBinDir(), "katago")
        if (extracted.exists() && extracted.canExecute()) {
            return extracted.absolutePath
        }

        val found = findBinaryFile("katago")
        return found?.absolutePath ?: ""
    }

    /**
     * 解析 LeelaZero 权重文件的最终路径。
     */
    fun resolveLeelaWeightsPath(): String {
        val custom = getLeelaWeightsPath()
        if (custom.isNotBlank() && File(custom).exists() && File(custom).canRead()) {
            return custom
        }

        val extracted = File(getWeightsDir(), ASSET_LEELA_WEIGHTS.substringAfterLast('/'))
        if (extracted.exists() && extracted.length() > 0) {
            return extracted.absolutePath
        }

        val found = findWeightFile(LEELA_WEIGHT_PATTERNS)
        return found?.absolutePath ?: ""
    }

    /**
     * 解析 LeelaZero 引擎可执行文件的最终路径。
     */
    fun resolveLeelaBinaryPath(): String {
        val custom = getLeelaBinaryPath()
        if (custom.isNotBlank() && File(custom).exists() && File(custom).canExecute()) {
            return custom
        }

        // nativeLibraryDir
        val nativeDir = appContext.applicationInfo.nativeLibraryDir
        val nativeFile = File(nativeDir, "libleelaz.so")
        if (nativeFile.exists() && nativeFile.canExecute()) {
            return nativeFile.absolutePath
        }

        val extracted = File(getBinDir(), "leelaz")
        if (extracted.exists() && extracted.canExecute()) {
            return extracted.absolutePath
        }

        val found = findBinaryFile("leelaz")
        return found?.absolutePath ?: ""
    }

    /**
     * 扫描公共目录查找匹配的权重文件。
     * @param patterns 文件名模式列表（如 ["katago", ".bin.gz"]）。
     */
    private fun findWeightFile(patterns: List<String>): File? {
        val scanDirs = listOf(
            File(Environment.getExternalStorageDirectory(), "Download"),
            getRecommendedWeightsDir(),
            File(Environment.getExternalStorageDirectory(), "Documents"),
        )
        for (dir in scanDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val name = file.name.lowercase()
                if (patterns.all { name.contains(it.lowercase()) }) {
                    return file
                }
            }
        }
        return null
    }

    /**
     * 扫描公共目录查找引擎可执行文件。
     * @param binaryName 可执行文件名称（"katago" / "leelaz"）。
     */
    private fun findBinaryFile(binaryName: String): File? {
        val scanDirs = listOf(
            getRecommendedBinDir(),
            File(Environment.getExternalStorageDirectory(), "Download"),
        )
        for (dir in scanDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val file = File(dir, binaryName)
            if (file.exists() && file.isFile) return file
            // 也检查带后缀的（如 katago.bin）
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile && f.name.startsWith(binaryName)) return f
            }
        }
        return null
    }

    private fun defaultKataGoConfig(): EngineConfig = EngineConfig(
        type = EngineType.KATAGO,
        weightsPath = "",
        threads = 2,
        maxVisits = 800,
        komi = 7.5,
        boardSize = 19,
        cpuOnly = true,
        enablePonder = false
    )

    private fun defaultLeelaConfig(): EngineConfig = EngineConfig(
        type = EngineType.LEELAZERO,
        weightsPath = "",
        threads = 2,
        maxVisits = 800,
        komi = 7.5,
        boardSize = 19,
        cpuOnly = true,
        enablePonder = false
    )

    private fun defaultRemoteConfig(): EngineConfig = EngineConfig(
        type = EngineType.REMOTE,
        weightsPath = "",
        threads = 0,
        maxVisits = 800,
        komi = 7.5,
        boardSize = 19,
        remoteHost = "",
        remotePort = 0,
        remotePassword = "",
        remotePlatform = "katago"
    )

    companion object {
        private const val PREFS_NAME = "weiqi_engine_prefs"
        private const val KEY_CURRENT_ENGINE = "current_engine_type"
        private const val KEY_KATAGO_CONFIG = "katago_config"
        private const val KEY_LEELA_CONFIG = "leela_config"
        private const val KEY_REMOTE_CONFIG = "remote_config"

        // 用户自定义路径键
        private const val KEY_KATAGO_WEIGHTS_PATH = "katago_weights_path"
        private const val KEY_KATAGO_BINARY_PATH = "katago_binary_path"
        private const val KEY_KATAGO_CONFIG_PATH = "katago_config_path"
        private const val KEY_LEELA_WEIGHTS_PATH = "leela_weights_path"
        private const val KEY_LEELA_BINARY_PATH = "leela_binary_path"

        internal const val ASSET_KATAGO_WEIGHTS = "weights/katago_b18c384.bin.gz"
        internal const val ASSET_LEELA_WEIGHTS = "weights/leelaz_b18c384.txt.gz"

        internal const val ASSET_KATAGO_BINARY = "bin/katago"
        internal const val ASSET_LEELA_BINARY = "bin/leelaz"

        internal const val ASSET_KATAGO_CONFIG = "config/katago_default.cfg"

        /** KataGo 权重文件识别模式：文件名包含 "katago" 且以 .bin.gz 或 .txt.gz 结尾。 */
        val KATAGO_WEIGHT_PATTERNS = listOf("katago", ".bin.gz")
        val LEELA_WEIGHT_PATTERNS = listOf("leela", ".txt.gz")
    }
}
