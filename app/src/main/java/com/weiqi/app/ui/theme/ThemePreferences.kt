package com.weiqi.app.ui.theme

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 应用级 DataStore 实例，用于持久化主题偏好。 */
private val Context.themeDataStore by preferencesDataStore(name = "weiqi_theme_prefs")

/**
 * 主题偏好持久化（基于 DataStore Preferences）。
 *
 * 存储键：
 * - `board_theme`：棋盘主题标识，默认 [BoardTheme.Classic]。
 * - `stone_theme`：棋子主题标识，默认 [StoneTheme.Standard]。
 * - `sound_enabled`：是否启用音效，默认 true。
 *
 * 所有读取接口返回冷 [Flow]，写入接口为 suspend。
 *
 * @param context 应用上下文（内部已取 applicationContext）。
 */
class ThemePreferences(private val context: Context) {

    private val store = context.applicationContext.themeDataStore

    /** 当前棋盘主题流。 */
    val boardTheme: Flow<BoardTheme> = store.data.map { prefs ->
        BoardTheme.byId(prefs[KEY_BOARD_THEME] ?: BoardTheme.Classic.id)
    }

    /** 当前棋子主题流。 */
    val stoneTheme: Flow<StoneTheme> = store.data.map { prefs ->
        StoneTheme.byId(prefs[KEY_STONE_THEME] ?: StoneTheme.Standard.id)
    }

    /** 是否启用音效流。 */
    val soundEnabled: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SOUND_ENABLED] ?: true
    }

    /** 是否显示坐标流。 */
    val showCoordinates: Flow<Boolean> = store.data.map { prefs ->
        prefs[KEY_SHOW_COORDINATES] ?: true
    }

    /** 持久化棋盘主题。 */
    suspend fun setBoardTheme(theme: BoardTheme) {
        store.edit { it[KEY_BOARD_THEME] = theme.id }
    }

    /** 持久化棋子主题。 */
    suspend fun setStoneTheme(theme: StoneTheme) {
        store.edit { it[KEY_STONE_THEME] = theme.id }
    }

    /** 持久化音效开关。 */
    suspend fun setSoundEnabled(enabled: Boolean) {
        store.edit { it[KEY_SOUND_ENABLED] = enabled }
    }

    /** 持久化坐标显示开关。 */
    suspend fun setShowCoordinates(show: Boolean) {
        store.edit { it[KEY_SHOW_COORDINATES] = show }
    }

    companion object {
        private val KEY_BOARD_THEME = stringPreferencesKey("board_theme")
        private val KEY_STONE_THEME = stringPreferencesKey("stone_theme")
        private val KEY_SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        private val KEY_SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
    }
}
