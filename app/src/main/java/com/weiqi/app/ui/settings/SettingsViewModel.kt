package com.weiqi.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weiqi.app.engine.DownloadStatus
import com.weiqi.app.engine.EngineConfig
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.engine.EnginePreferences
import com.weiqi.app.engine.EngineType
import com.weiqi.app.engine.WeightDownloadManager
import com.weiqi.app.engine.WeightInfo
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
 */
class SettingsViewModel(
    application: Application,
    private val engineManager: EngineManager,
    private val themePreferences: ThemePreferences,
    private val enginePreferences: EnginePreferences
) : AndroidViewModel(application) {

    private val _settingsState = MutableStateFlow(buildInitialState())
    val settingsState: StateFlow<SettingsUiState> = _settingsState.asStateFlow()

    private val downloadManager = WeightDownloadManager(application)

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
        // 监听下载完成
        viewModelScope.launch {
            downloadManager.downloadCompleteFlow().collect { downloadId ->
                refreshDownloadStates()
                // 自动刷新权重路径，让下载完成的文件能被识别
                refreshPaths()
            }
        }
        // 初始查询已有下载状态
        viewModelScope.launch { refreshDownloadStates() }
    }

    // ===== 权重下载 =====

    /** 开始下载指定权重。 */
    fun downloadWeight(weight: WeightInfo) {
        val id = downloadManager.startDownload(weight)
        _settingsState.value = _settingsState.value.copy(
            downloadingWeightKey = weight.key
        )
        viewModelScope.launch { refreshDownloadStates() }
    }

    /** 刷新所有权重的下载状态，下载完成时自动关联到 katagoWeightsPath。 */
    fun refreshDownloadStates() {
        val states = WeightInfo.ALL.associate { weight ->
            val lastId = downloadManager.getLastDownloadId(weight.key)
            val status = if (lastId >= 0) downloadManager.queryStatus(lastId) else DownloadStatus.NotFound
            // 下载完成后，自动将路径关联到当前文件的预期位置
            if (status is DownloadStatus.Completed) {
                val downloadedFile = File(
                    android.os.Environment.getExternalStoragePublicDirectory(
                        android.os.Environment.DIRECTORY_DOWNLOADS
                    ),
                    weight.fileName
                )
                if (downloadedFile.exists() && enginePreferences.getKataGoWeightsPath().isBlank()) {
                    enginePreferences.setKataGoWeightsPath(downloadedFile.absolutePath)
                    _settingsState.value = _settingsState.value.copy(
                        katagoWeightsPath = downloadedFile.absolutePath
                    )
                }
            }
            weight.key to status
        }
        _settingsState.value = _settingsState.value.copy(weightDownloadStates = states)
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

    fun onKataGoWeightsFileSelected(uri: Uri, path: String) {
        enginePreferences.setKataGoWeightsPath(path)
        _settingsState.value = _settingsState.value.copy(katagoWeightsPath = path)
    }

    fun onKataGoBinaryFileSelected(uri: Uri, path: String) {
        enginePreferences.setKataGoBinaryPath(path)
        _settingsState.value = _settingsState.value.copy(katagoBinaryPath = path)
    }

    fun onLeelaWeightsFileSelected(uri: Uri, path: String) {
        enginePreferences.setLeelaWeightsPath(path)
        _settingsState.value = _settingsState.value.copy(leelaWeightsPath = path)
    }

    fun onLeelaBinaryFileSelected(uri: Uri, path: String) {
        enginePreferences.setLeelaBinaryPath(path)
        _settingsState.value = _settingsState.value.copy(leelaBinaryPath = path)
    }

    fun clearKataGoWeightsPath() {
        enginePreferences.setKataGoWeightsPath("")
        _settingsState.value = _settingsState.value.copy(katagoWeightsPath = "")
    }

    fun clearKataGoBinaryPath() {
        enginePreferences.setKataGoBinaryPath("")
        _settingsState.value = _settingsState.value.copy(katagoBinaryPath = "")
    }

    fun clearLeelaWeightsPath() {
        enginePreferences.setLeelaWeightsPath("")
        _settingsState.value = _settingsState.value.copy(leelaWeightsPath = "")
    }

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
        fun resolveFilePath(uri: Uri): String {
            if (uri.scheme == "file") {
                return uri.path ?: ""
            }
            val path = uri.path ?: ""
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
    val weightDownloadStates: Map<String, DownloadStatus> = emptyMap(),
    val downloadingWeightKey: String? = null,
    val boardTheme: BoardTheme,
    val stoneTheme: StoneTheme,
    val soundEnabled: Boolean,
    val engineRunning: Boolean,
    val lastError: String? = null
)
