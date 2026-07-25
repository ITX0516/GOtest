package com.weiqi.app.engine

/**
 * 围棋引擎类型枚举。
 *
 * @property displayName 用于 UI 显示的名称。
 */
enum class EngineType(val displayName: String) {
    KATAGO("KataGo"),
    LEELAZERO("LeelaZero"),
    REMOTE("远程计算");

    companion object {
        /**
         * 由名称字符串反查引擎类型，支持中英文与大小写变体。
         * @param name 引擎名称（如 "KataGo"、"leelazero"、"远程"）。
         * @return 匹配的 [EngineType]；未匹配返回 null。
         */
        fun fromName(name: String): EngineType? {
            val key = name.trim().lowercase()
            return when (key) {
                "katago" -> KATAGO
                "leelazero", "leela zero", "leela-zero", "leela" -> LEELAZERO
                "remote", "远程", "远程计算" -> REMOTE
                else -> null
            }
        }
    }
}
