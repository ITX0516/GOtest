package com.weiqi.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.weiqi.app.WeiqiApp
import com.weiqi.app.ui.analysis.AnalysisViewModel
import com.weiqi.app.ui.play.PlayViewModel
import com.weiqi.app.ui.settings.SettingsViewModel

/**
 * 应用级单例工厂。
 *
 * 把 [WeiqiApp] 中初始化的 EngineManager / SoundManager / ThemePreferences
 * 注入到各 ViewModel，避免引入完整 DI 框架。
 */
class PlayViewModelFactory(
    private val app: WeiqiApp
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return PlayViewModel(
            engineManager = app.engineManager,
            soundManager = app.soundManager,
            themePreferences = app.themePreferences,
            savedStateHandle = androidx.lifecycle.SavedStateHandle()
        ) as T
    }
}

class AnalysisViewModelFactory(
    private val app: WeiqiApp,
    private val initialSgf: String = ""
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val handle = androidx.lifecycle.SavedStateHandle()
        if (initialSgf.isNotEmpty()) handle["sgf"] = initialSgf
        return AnalysisViewModel(
            engineManager = app.engineManager,
            themePreferences = app.themePreferences,
            savedStateHandle = handle
        ) as T
    }
}

class SettingsViewModelFactory(
    private val app: WeiqiApp
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(
            application = app,
            engineManager = app.engineManager,
            themePreferences = app.themePreferences,
            enginePreferences = app.enginePreferences
        ) as T
    }
}
