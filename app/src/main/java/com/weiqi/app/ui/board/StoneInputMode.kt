package com.weiqi.app.ui.board

/**
 * 棋盘落子交互模式。
 *
 * - [SINGLE_TAP]：单击交点直接落子。
 * - [DOUBLE_TAP]：第一次点击显示半透明预览棋子，第二次点击（双击）确认落子。
 * - [CONFIRM_BUTTON]：点击选中待确认点（高亮），点击右下角"确认"按钮后才真正落子。
 *
 * @property displayName 用户可见的显示名称。
 */
enum class StoneInputMode(val displayName: String) {
    /** 单击落子。 */
    SINGLE_TAP("单击落子"),

    /** 双击落子。 */
    DOUBLE_TAP("双击落子"),

    /** 确认按钮落子。 */
    CONFIRM_BUTTON("确认按钮")
}
