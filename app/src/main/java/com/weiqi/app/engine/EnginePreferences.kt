package com.weiqi.app.engine

import android.content.Context
import java.io.File

/**
 * 引擎偏好设置持久化。
 *
 * 基于 SharedPreferences 存储：
 * - 当前选中引擎类型
 * - KataGo / LeelaZero / 远程引擎的 [EngineConfig]
 *
 * 真实权重文件说明（用户需自行下载）：
 * - KataGo b18c384 约 40MB，放置于 `app/src/main/assets/weights/katago_b18c384.bin.gz`
 * - LeelaZero b18c384 约 50MB，放置于 `app/src/main/assets/weights/leelaz_b18c384.txt.gz`
 *
 * 真实引擎 .so 说明（用户需自行编译或下载）：
 * - `libkatago.so` / `libleelaz.so` 放置于 `app/src/main/jniLibs/<abi>/`
 *   （`<abi>` 为 `arm64-v8a` / `x86_64` 等）
 * - 并在 `app/src/main/cpp/CMakeLists.txt` 中开启
 *   `-DWEIQI_LINK_KATAGO=ON` / `-DWEIQI_LINK_LEELAZERO=ON`，
 *   设置 `KATAGO_LIB_DIR` / `LEELAZERO_LIB_DIR` 指向 .so 所在目录
 *
 * Stub 模式（默认）：未链接真实引擎时，C++ 端构建桩实现，
 * genmove 返回随机合法点或 pass，仅用于 UI 调试。
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
     * 引擎启动前由 [EngineManager.ensureWeightsExtracted] 解压/复制到这里。
     */
    fun getWeightsDir(): File {
        val dir = File(appContext.filesDir, "weights")
        if (!dir.exists()) dir.mkdirs()
        return dir
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

        internal const val ASSET_KATAGO_WEIGHTS = "weights/katago_b18c384.bin.gz"
        internal const val ASSET_LEELA_WEIGHTS = "weights/leelaz_b18c384.txt.gz"

        internal const val ASSET_KATAGO_BINARY = "bin/katago"
        internal const val ASSET_LEELA_BINARY = "bin/leelaz"

        internal const val ASSET_KATAGO_CONFIG = "config/katago_default.cfg"
    }
}
