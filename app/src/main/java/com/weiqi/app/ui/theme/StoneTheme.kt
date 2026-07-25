package com.weiqi.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 围棋棋子视觉主题。
 *
 * 描述黑白棋子的填充色与高光色，以及落子阴影透明度。
 * 径向渐变会使用高光色作为光源中心，营造立体感。
 *
 * @property id 主题唯一标识。
 * @property displayName 用户可见的显示名称。
 * @property blackFill 黑子主体填充色。
 * @property blackHighlight 黑子高光色（光源来自左上角）。
 * @property whiteFill 白子主体填充色。
 * @property whiteHighlight 白子高光色。
 * @property shadowAlpha 落子阴影透明度，0..1。
 */
data class StoneTheme(
    val id: String,
    val displayName: String,
    val blackFill: Color,
    val blackHighlight: Color,
    val whiteFill: Color,
    val whiteHighlight: Color,
    val shadowAlpha: Float = 0.35f
) {
    companion object {
        /** 标准云子。 */
        val Standard = StoneTheme("standard", "标准", Color(0xFF1A1A1A), Color(0xFF555555), Color(0xFFFAFAFA), Color(0xFFFFFFFF))

        /** 玉石质感棋子。 */
        val Jade = StoneTheme("jade", "玉石", Color(0xFF0D2A1A), Color(0xFF2E7A55), Color(0xFFE8F0E0), Color(0xFFFFFFFF))

        /** 石板质感棋子。 */
        val Slate = StoneTheme("slate", "石板", Color(0xFF252525), Color(0xFF666666), Color(0xFFC8C8C8), Color(0xFFFFFFFF))

        /** 全部预设主题列表。 */
        val DEFAULTS: List<StoneTheme> = listOf(Standard, Jade, Slate)

        /**
         * 按标识符查找预设主题，未命中时回退到 [Standard]。
         * @param id 主题标识。
         */
        fun byId(id: String): StoneTheme = DEFAULTS.firstOrNull { it.id == id } ?: Standard
    }
}
