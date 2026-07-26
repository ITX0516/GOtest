package com.weiqi.app.ui.settings

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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置页 ViewModel。
 *
 * 持有：
 * - 引擎选择与各引擎参数
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
            boardTheme = BoardTheme.Classic,
            stoneTheme = StoneTheme.Standard,
            soundEnabled = true,
            engineRunning = engineManager.isEngineRunning
        )
    }

    init {
        // 订阅主题流，反映到 UI
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

    /** 获取当前权重目录（外部存储，无 root 也能访问）。 */
    fun getWeightsDir(): java.io.File? = runCatching { enginePreferences.getWeightsDir() }.getOrNull()

    /** 展示一条提示信息（由 LaunchedEffect 读取后清除）。 */
    fun showInfo(message: String) {
        _settingsState.value = _settingsState.value.copy(infoMessage = message)
    }

    fun clearInfo() {
        _settingsState.value = _settingsState.value.copy(infoMessage = null)
    }
}

data class SettingsUiState(
    val currentEngine: EngineType,
    val katagoConfig: EngineConfig,
    val leelaConfig: EngineConfig,
    val remoteConfig: EngineConfig,
    val boardTheme: BoardTheme,
    val stoneTheme: StoneTheme,
    val soundEnabled: Boolean,
    val engineRunning: Boolean,
    val lastError: String? = null,
    val infoMessage: String? = null
)
