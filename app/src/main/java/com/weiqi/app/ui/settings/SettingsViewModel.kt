package com.weiqi.app.ui.settings

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weiqi.app.engine.EngineConfig
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.engine.EnginePreferences
import com.weiqi.app.engine.EngineType
import com.weiqi.app.ui.theme.BoardTheme
import com.weiqi.app.ui.theme.StoneTheme
import com.weiqi.app.ui.theme.ThemePreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // 用户通过系统文件选择器(SAF)选择任意文件后，将文件内容复制到 app 私有目录，
    // 确保引擎子进程能可靠读取（Android 10+ scoped storage 下 /sdcard 路径可能不可访问）。

    fun onKataGoWeightsFileSelected(uri: Uri) {
        viewModelScope.launch {
            val path = copyUriToPrivateDir(uri, "weights", "katago_weights.bin.gz")
            if (path != null) {
                enginePreferences.setKataGoWeightsPath(path)
                _settingsState.value = _settingsState.value.copy(katagoWeightsPath = path)
            } else {
                _settingsState.value = _settingsState.value.copy(
                    lastError = "无法读取选中的权重文件"
                )
            }
        }
    }

    fun onKataGoBinaryFileSelected(uri: Uri) {
        viewModelScope.launch {
            val path = copyUriToPrivateDir(uri, "bin", "katago", setExecutable = true)
            if (path != null) {
                enginePreferences.setKataGoBinaryPath(path)
                _settingsState.value = _settingsState.value.copy(katagoBinaryPath = path)
            } else {
                _settingsState.value = _settingsState.value.copy(
                    lastError = "无法读取选中的引擎文件"
                )
            }
        }
    }

    fun onLeelaWeightsFileSelected(uri: Uri) {
        viewModelScope.launch {
            val path = copyUriToPrivateDir(uri, "weights", "leela_weights.txt.gz")
            if (path != null) {
                enginePreferences.setLeelaWeightsPath(path)
                _settingsState.value = _settingsState.value.copy(leelaWeightsPath = path)
            } else {
                _settingsState.value = _settingsState.value.copy(
                    lastError = "无法读取选中的权重文件"
                )
            }
        }
    }

    fun onLeelaBinaryFileSelected(uri: Uri) {
        viewModelScope.launch {
            val path = copyUriToPrivateDir(uri, "bin", "leelaz", setExecutable = true)
            if (path != null) {
                enginePreferences.setLeelaBinaryPath(path)
                _settingsState.value = _settingsState.value.copy(leelaBinaryPath = path)
            } else {
                _settingsState.value = _settingsState.value.copy(
                    lastError = "无法读取选中的引擎文件"
                )
            }
        }
    }

    /**
     * 将 SAF URI 对应的文件复制到 app 私有目录，确保引擎子进程能可靠读取/执行。
     *
     * - 权重文件（setExecutable=false）：复制到 filesDir/<subdir>/<fileName>
     * - 引擎二进制（setExecutable=true）：复制到 nativeLibraryDir/lib<defaultName>.so
     *   Android 10+ 的 W^X（Write XOR Execute）策略禁止从 filesDir 执行二进制文件，
     *   即使 chmod +x 也会被 SELinux 拒绝；唯一可靠的可执行目录是 nativeLibraryDir，
     *   且文件名必须是 lib*.so 格式（PackageManager 解压 jniLibs 时保留 +x）。
     *   nativeLibraryDir 不可写时回退到 filesDir/bin/（Android 9 及以下可执行）。
     *
     * @param uri 文件选择器返回的 content:// URI
     * @param subdir 私有目录下的子目录名（如 "weights"、"bin"，仅非可执行文件用）
     * @param defaultName 无法从 URI 获取文件名时使用的默认名；可执行文件会变成 lib<defaultName>.so
     * @param setExecutable 是否作为可执行文件处理（引擎二进制文件需要）
     * @return 复制后的文件绝对路径；失败返回 null
     */
    private suspend fun copyUriToPrivateDir(
        uri: Uri,
        subdir: String,
        defaultName: String,
        setExecutable: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        val context = getApplication<Application>()
        val fileName = getFileNameFromUri(uri) ?: defaultName

        // 二进制文件：优先放 nativeLibraryDir，命名 lib*.so（W^X 策略下唯一可靠）
        if (setExecutable) {
            val nativePath = copyToNativeLibraryDir(context, uri, defaultName)
            if (nativePath != null) return@withContext nativePath

            // nativeLibraryDir 不可写时回退（Android 9- 可执行；Android 10+ 会报 Broken pipe）
            val fallbackDir = File(context.filesDir, subdir)
            if (!fallbackDir.exists()) fallbackDir.mkdirs()
            val fallbackFile = File(fallbackDir, fileName)
            val ok = try {
                context.contentResolver.openInputStream(uri).use { input ->
                    if (input == null) return@withContext null
                    fallbackFile.outputStream().use { output -> input.copyTo(output) }
                }
                fallbackFile.setExecutable(true, false)
                true
            } catch (_: Exception) {
                false
            }
            return@withContext if (ok) fallbackFile.absolutePath else null
        }

        // 普通文件：放 filesDir/<subdir>/<fileName>
        try {
            val destDir = File(context.filesDir, subdir)
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileName)
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return@withContext null
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            destFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 复制到 nativeLibraryDir 并命名 lib<name>.so。
     * Android 10+ W^X 策略下，这是唯一能可靠执行用户所选二进制的方式。
     */
    private fun copyToNativeLibraryDir(
        context: Application,
        uri: Uri,
        defaultName: String
    ): String? {
        return try {
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            if (!nativeDir.exists()) nativeDir.mkdirs()
            // 必须用 lib*.so 命名，否则 Android 不允许执行
            val destFile = File(nativeDir, "lib$defaultName.so")
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
            // nativeLibraryDir 下的 .so 默认有 +x；显式设置兜底
            destFile.setExecutable(true, false)
            destFile.absolutePath
        } catch (_: Exception) {
            // nativeLibraryDir 只读或写入失败
            null
        }
    }

    /** 从 SAF URI 查询文件显示名。 */
    private fun getFileNameFromUri(uri: Uri): String? {
        val context = getApplication<Application>()
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        } catch (_: Exception) {
            null
        }
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
