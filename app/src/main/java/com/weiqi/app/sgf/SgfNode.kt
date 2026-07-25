package com.weiqi.app.sgf

/**
 * SGF 节点。
 *
 * 一个节点保存若干 SGF 属性，并按树形结构持有子节点（变着）。
 * 第一个子节点通常为主线分支。
 *
 * @property properties 节点属性表，键为属性标识（如 "B"），值为该属性的值列表。
 *           一个属性可携带多个值，例如 `AB[ab][cd]` 解析为 `{"AB" -> ["ab", "cd"]}`。
 * @property children 子节点列表，按出现顺序排列；`children.firstOrNull()` 为主线。
 * @property parent 父节点引用，根节点为 null。
 */
data class SgfNode(
    val properties: MutableMap<String, List<String>> = mutableMapOf(),
    val children: MutableList<SgfNode> = mutableListOf(),
    var parent: SgfNode? = null
) {
    /**
     * 通过属性标识读取值列表。
     * @return 该属性对应的值列表；不存在时返回 null。
     */
    operator fun get(key: String): List<String>? = properties[key]

    /**
     * 设置单值属性，覆盖原有值。
     */
    fun setProperty(key: String, value: String) {
        properties[key] = listOf(value)
    }

    /**
     * 设置多值属性，覆盖原有值。
     */
    fun setProperty(key: String, values: List<String>) {
        properties[key] = values
    }

    /**
     * 追加一个值到指定属性，若属性不存在则新建。
     */
    fun addProperty(key: String, value: String) {
        val existing = properties[key]
        properties[key] = if (existing == null) listOf(value) else existing + value
    }

    /** 移除指定属性。 */
    fun removeProperty(key: String) {
        properties.remove(key)
    }

    /** 判断节点是否含有指定属性。 */
    fun hasProperty(key: String): Boolean = properties.containsKey(key)

    /** 判断本节点是否为根（无父节点）。 */
    fun isRoot(): Boolean = parent == null

    /** 判断本节点是否为走子节点（含 B 或 W 属性）。 */
    fun isMove(): Boolean =
        hasProperty(SgfProperty.BLACK_MOVE) || hasProperty(SgfProperty.WHITE_MOVE)

    /**
     * 获取本节点的走子方颜色。
     * @return "B" 或 "W"；若非走子节点则返回 null。
     */
    fun moveColor(): String? = when {
        hasProperty(SgfProperty.BLACK_MOVE) -> SgfProperty.BLACK_MOVE
        hasProperty(SgfProperty.WHITE_MOVE) -> SgfProperty.WHITE_MOVE
        else -> null
    }

    /**
     * 获取本节点走子坐标。
     * @return SGF 坐标字符串（如 "ab"）；空字符串表示 pass；若非走子节点返回 null。
     */
    fun moveCoord(): String? {
        val color = moveColor() ?: return null
        return this[color]?.firstOrNull() ?: ""
    }

    /** 获取节点注释文本，无注释时返回 null。 */
    fun comment(): String? = this[SgfProperty.COMMENT]?.firstOrNull()

    /**
     * 添加子节点，并设置其 parent 引用。
     * @return 已添加的子节点，便于链式调用。
     */
    fun addChild(node: SgfNode): SgfNode {
        node.parent = this
        children.add(node)
        return node
    }

    /** 距根节点的深度，根节点为 0。 */
    fun depth(): Int {
        var d = 0
        var p = parent
        while (p != null) {
            d++
            p = p.parent
        }
        return d
    }

    /** 从根到本节点的路径列表（包含两端，根节点在前）。 */
    fun pathFromRoot(): List<SgfNode> {
        val path = mutableListOf<SgfNode>()
        var n: SgfNode? = this
        while (n != null) {
            path.add(0, n)
            n = n.parent
        }
        return path
    }
}
