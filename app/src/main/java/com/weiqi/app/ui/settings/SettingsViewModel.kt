package com.weiqi.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.weiqi.app.engine.EngineConfig
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.engine.EnginePreferences
import com.weiqi.app.engine.EngineType
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme
import com.weiqi.app.ui.theme.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置页 ViewModel。
 *
 * 持有：
 * - 引擎选择与各引擎参数
 * - 用户自定义权重/引擎二进制路径
 * - 远程算力平台配置
 * - 主题（棋盘 / 棋子 / 音效）
 *
 * 修改后立即持久化到 [EnginePreferences] / [ThemePreferences]，
 * 当前运行的引擎若被修改可调用 [restartEngine] 重启以应用新配置。
 */
class SettingsViewModel(
    private val engineManager: EngineManager,
    private val themePreferences: ThemePreferences,
    private val enginePreferences: EnginePreferences
) : ViewModel() {

    private val _settingsState = MutableStateFlow(buildInitialState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    /** 设备是否支持内置引擎（ABI / 内存检查）。 */
    val deviceSupported: StateFlow<Map<EngineType, Boolean>> = MutableStateFlow(
        mapOf(
            EngineType.KATAGO to engineManager.deviceSupportsEngine(EngineType.KATAGO),
            EngineType.LEELAZERO to engineManager.deviceSupportsEngine(EngineType.LEELAZERO),
            EngineType.REMOTE to true
        )
    ).asStateFlow()

    private fun buildInitialState(): SettingsUiState {
        return SettingsUiState(
            currentEngine = enginePreferences.getCurrentEngineType(),
            katagoConfig = enginePreferences.getKataGoConfig(),
            leelaConfig = enginePreferences.getLeelaZeroConfig(),
            remoteConfig = enginePreferences.getRemoteConfig(),
            katagoWeightsPath = enginePreferences.getKataGoWeightsPath(),
            katagoBinaryPath = enginePreferences.getKataGoBinaryPath(),
            leelaWeightsPath = enginePreferences.getLeelaWeightsPath(),
            leelaBinaryPath = enginePreferences.getLeelaBinaryPath(),
            boardTheme = BoardTheme.Classic,
            stoneTheme = StoneTheme.Standard,
            soundEnabled = true,
            engineRunning = engineManager.isEngineRunning
        )
    }

    /** 刷新路径状态（用户可能通过文件管理器移走了文件）。 */
    fun refreshPaths() {
        _settingsState.value = _settingsState.value.copy(
            katagoWeightsPath = enginePreferences.getKataGoWeightsPath(),
            katagoBinaryPath = enginePreferences.getKataGoBinaryPath(),
            leelaWeightsPath = enginePreferences.getLeelaWeightsPath(),
            leelaBinaryPath = enginePreferences.getLeelaBinaryPath()
        )
    }

    init {
        viewModelScope.launch {
            themePreferences.boardTheme.collect { theme ->
                _settingsState.value = _settingsState.value.copy(boardTheme = theme)
            }
        }
        viewModelScope.launch {
            themePreferences.stoneTheme.collect { theme ->
                _settingsState.value = _settingsState.value.copy(stoneTheme = theme)
            }
        }
        viewModelScope.launch {
            themePreferences.soundEnabled.collect { enabled ->
                _settingsState.value = _settingsState.value.copy(soundEnabled = enabled)
            }
        }
    }

    fun setCurrentEngine(type: EngineType) {
        enginePreferences.setCurrentEngineType(type)
        _settingsState.value = _settingsState.value.copy(currentEngine = type)
    }

    fun updateKataGoConfig(config: EngineConfig) {
        require(config.type == EngineType.KATAGO)
        enginePreferences.setKataGoConfig(config)
        _settingsState.value = _settingsState.value.copy(katagoConfig = config)
    }

    fun updateLeelaConfig(config: EngineConfig) {
        require(config.type == EngineType.LEELAZERO)
        enginePreferences.setLeelaZeroConfig(config)
        _settingsState.value = _settingsState.value.copy(leelaConfig = config)
    }

    fun updateRemoteConfig(config: EngineConfig) {
        require(config.type == EngineType.REMOTE)
        enginePreferences.setRemoteConfig(config)
        _settingsState.value = _settingsState.value.copy(remoteConfig = config)
    }

    // ===== 文件选择回调 =====

    /** 处理用户选择的 KataGo 权重文件 URI。 */
    fun onKataGoWeightsFileSelected(uri: Uri, path: String) {
        enginePreferences.setKataGoWeightsPath(path)
        _settingsState.value = _settingsState.value.copy(katagoWeightsPath = path)
    }

    /** 处理用户选择的 KataGo 引擎二进制文件 URI。 */
    fun onKataGoBinaryFileSelected(uri: Uri, path: String) {
        enginePreferences.setKataGoBinaryPath(path)
        _settingsState.value = _settingsState.value.copy(katagoBinaryPath = path)
    }

    /** 处理用户选择的 LeelaZero 权重文件 URI。 */
    fun onLeelaWeightsFileSelected(uri: Uri, path: String) {
        enginePreferences.setLeelaWeightsPath(path)
        _settingsState.value = _settingsState.value.copy(leelaWeightsPath = path)
    }

    /** 处理用户选择的 LeelaZero 引擎二进制文件 URI。 */
    fun onLeelaBinaryFileSelected(uri: Uri, path: String) {
        enginePreferences.setLeelaBinaryPath(path)
        _settingsState.value = _settingsState.value.copy(leelaBinaryPath = path)
    }

    /** 清除 KataGo 权重路径。 */
    fun clearKataGoWeightsPath() {
        enginePreferences.setKataGoWeightsPath("")
        _settingsState.value = _settingsState.value.copy(katagoWeightsPath = "")
    }

    /** 清除 KataGo 二进制路径。 */
    fun clearKataGoBinaryPath() {
        enginePreferences.setKataGoBinaryPath("")
        _settingsState.value = _settingsState.value.copy(katagoBinaryPath = "")
    }

    /** 清除 LeelaZero 权重路径。 */
    fun clearLeelaWeightsPath() {
        enginePreferences.setLeelaWeightsPath("")
        _settingsState.value = _settingsState.value.copy(leelaWeightsPath = "")
    }

    /** 清除 LeelaZero 二进制路径。 */
    fun clearLeelaBinaryPath() {
        enginePreferences.setLeelaBinaryPath("")
        _settingsState.value = _settingsState.value.copy(leelaBinaryPath = "")
    }

    fun setBoardTheme(theme: BoardTheme) {
        viewModelScope.launch { themePreferences.setBoardTheme(theme) }
    }

    fun setStoneTheme(theme: StoneTheme) {
        viewModelScope.launch { themePreferences.setStoneTheme(theme) }
    }

    fun setSoundEnabled(enabled: Boolean) {
        viewModelScope.launch { themePreferences.setSoundEnabled(enabled) }
    }

    /** 切换当前引擎并启动；返回失败信息到 [SettingsUiState.lastError]。 */
    fun restartEngine() {
        viewModelScope.launch {
            try {
                engineManager.switchEngine(_settingsState.value.currentEngine)
                _settingsState.value = _settingsState.value.copy(
                    engineRunning = true,
                    lastError = null
                )
            } catch (e: Throwable) {
                _settingsState.value = _settingsState.value.copy(
                    engineRunning = false,
                    lastError = e.message ?: "引擎启动失败"
                )
            }
        }
    }

    fun stopEngine() {
        viewModelScope.launch {
            engineManager.stopEngine()
            _settingsState.value = _settingsState.value.copy(engineRunning = false)
        }
    }

    fun clearError() {
        _settingsState.value = _settingsState.value.copy(lastError = null)
    }

    companion object {
        /**
         * 从 content URI 解析出实际文件路径（用于 SAF 文件选择器回调）。
         * 如果无法解析，回退到 URI 的路径部分。
         */
        fun resolveFilePath(uri: Uri): String {
            // 常见：file:/// 前缀的 URI 直接返回路径
            if (uri.scheme == "file") {
                return uri.path ?: ""
            }
            // content:// URI — 尝试解析为实际路径
            val path = uri.path ?: ""
            // 常见 content URI 格式: content://.../document/primary:Download/katago.bin.gz
            val primaryIdx = path.indexOf("/primary:")
            if (primaryIdx >= 0) {
                return "/sdcard/" + path.substring(primaryIdx + "/primary:".length)
            }
            val sdcardIdx = path.indexOf("/SDCARD:")
            if (sdcardIdx >= 0) {
                return "/sdcard/" + path.substring(sdcardIdx + "/SDCARD:".length)
            }
            return path
        }
    }
}

data class SettingsUiState(
    val currentEngine: EngineType,
    val katagoConfig: EngineConfig,
    val leelaConfig: EngineConfig,
    val remoteConfig: EngineConfig,
    val katagoWeightsPath: String = "",
    val katagoBinaryPath: String = "",
    val leelaWeightsPath: String = "",
    val leelaBinaryPath: String = "",
    val boardTheme: BoardTheme,
    val stoneTheme: StoneTheme,
    val soundEnabled: Boolean,
    val engineRunning: Boolean,
    val lastError: String? = null
)
