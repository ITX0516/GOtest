package com.weiqi.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 围棋棋盘视觉主题。
 *
 * 描述棋盘底色、网格线、星位、坐标等元素的颜色，以及可选的木质纹理资源路径。
 * 内置四套预设主题（经典原木 / 宣纸 / 竹简 / 深色），可通过 [byId] 按标识符查找。
 *
 * @property id 主题唯一标识，用于持久化偏好。
 * @property displayName 用户可见的显示名称。
 * @property boardColor 棋盘底色。
 * @property lineColor 网格线与外框颜色。
 * @property starPointColor 星位颜色。
 * @property coordinateColor 坐标字母/数字颜色。
 * @property woodTexturePath 可选纹理资源路径（相对 assets），为 null 表示纯色。
 */
data class BoardTheme(
    val id: String,
    val displayName: String,
    val boardColor: Color,
    val lineColor: Color,
    val starPointColor: Color,
    val coordinateColor: Color,
    val woodTexturePath: String? = null
) {
    companion object {
        /** 经典原木色棋盘。 */
        val Classic = BoardTheme("classic", "经典原木", Color(0xFFE8B964), Color(0xFF3A2E1C), Color(0xFF3A2E1C), Color(0xFF6B5A3F))

        /** 宣纸风格棋盘。 */
        val Paper = BoardTheme("paper", "宣纸", Color(0xFFD9C28A), Color(0xFF5A4A30), Color(0xFF5A4A30), Color(0xFF7A6A50))

        /** 竹简风格棋盘。 */
        val Bamboo = BoardTheme("bamboo", "竹简", Color(0xFFA8B87C), Color(0xFF3A2E1C), Color(0xFF3A2E1C), Color(0xFF5A4A30))

        /** 深色棋盘（适合夜间）。 */
        val Dark = BoardTheme("dark", "深色", Color(0xFF3D3320), Color(0xFFD4B876), Color(0xFFD4B876), Color(0xFFA89570))

        /** 全部预设主题列表。 */
        val DEFAULTS: List<BoardTheme> = listOf(Classic, Paper, Bamboo, Dark)

        /**
         * 按标识符查找预设主题，未命中时回退到 [Classic]。
         * @param id 主题标识。
         */
        fun byId(id: String): BoardTheme = DEFAULTS.firstOrNull { it.id == id } ?: Classic
    }
}
