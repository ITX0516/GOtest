package com.weiqi.app

import android.app.Application
import com.weiqi.app.engine.EngineManager
import com.weiqi.app.engine.EnginePreferences
import com.weiqi.app.ui.theme.SoundManager
import com.weiqi.app.ui.theme.ThemePreferences
import com.weiqi.app.util.AppLogger

/**
 * 应用入口，初始化全局单例。
 *
 * 手动 DI：在 onCreate 中创建一次，提供给 ViewModel Factory 使用。
 * 不引入 Hilt/Koin 等框架，保持项目轻量。
 */
class WeiqiApp : Application() {
    lateinit var enginePreferences: EnginePreferences
        private set
    lateinit var engineManager: EngineManager
        private set
    lateinit var themePreferences: ThemePreferences
        private set
    lateinit var soundManager: SoundManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // 日志系统必须最先初始化（其他模块都会用它）
        AppLogger.init(this)
        AppLogger.i("WeiqiApp", "Application.onCreate 开始")

        enginePreferences = EnginePreferences(this)
        engineManager = EngineManager(this, enginePreferences)
        themePreferences = ThemePreferences(this)
        soundManager = SoundManager(this)

        AppLogger.i("WeiqiApp", "Application.onCreate 完成")
    }

    companion object {
        lateinit var instance: WeiqiApp
            private set
    }
}
