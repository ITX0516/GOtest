package com.weiqi.app.sgf

/**
 * SGF 序列化器。
 *
 * 将 [SgfGameTree] 输出为符合 FF[4] 的 SGF 文本，支持 compact 与 pretty 两种格式：
 * - compact：单行紧凑输出，适合存储与传输
 * - pretty：每节点换行+缩进，便于阅读；变着以独立括号包裹
 *
 * 序列化时正确转义属性值中的 `]` 与 `\`。
 */
object SgfSerializer {
    /**
     * 序列化游戏树。
     * @param tree 游戏树。
     * @param pretty 是否美化输出（每节点换行+缩进）。
     * @return SGF 文本，外层用括号包裹。
     */
    fun serialize(tree: SgfGameTree, pretty: Boolean = false): String {
        val sb = StringBuilder()
        sb.append('(')
        if (pretty) sb.append('\n')
        serializeNodeRecursive(tree.root, sb, pretty, 1)
        sb.append(')')
        return sb.toString()
    }

    /**
     * 序列化单个节点（不含子树），形如 `;B[ab]C[comment]`。
     * @param node 待序列化的节点。
     */
    fun serializeNode(node: SgfNode): String {
        val sb = StringBuilder()
        appendNodeBody(node, sb)
        return sb.toString()
    }

    /** 追加节点体（`;` + 属性列表）到 [sb]。 */
    private fun appendNodeBody(node: SgfNode, sb: StringBuilder) {
        sb.append(';')
        for ((ident, values) in node.properties) {
            sb.append(ident)
            for (v in values) {
                sb.append('[').append(escapeValue(v)).append(']')
            }
        }
    }

    /**
     * 递归序列化节点及其子树。
     * - 单子节点：直接续写为主干 Sequence
     * - 多子节点：每个子节点各自包裹在括号中作为变着
     */
    private fun serializeNodeRecursive(
        node: SgfNode,
        sb: StringBuilder,
        pretty: Boolean,
        indent: Int
    ) {
        if (pretty) sb.append("  ".repeat(indent))
        appendNodeBody(node, sb)
        if (pretty) sb.append('\n')

        val children = node.children
        if (children.isEmpty()) return
        if (children.size == 1) {
            // 单子节点：续写为主干
            serializeNodeRecursive(children.first(), sb, pretty, indent)
        } else {
            // 多子节点：每个变着独立括号
            for (child in children) {
                sb.append('(')
                if (pretty) sb.append('\n')
                serializeNodeRecursive(child, sb, pretty, indent + 1)
                if (pretty) sb.append("  ".repeat(indent))
                sb.append(')')
                if (pretty) sb.append('\n')
            }
        }
    }

    /**
     * 转义 SGF 属性值中的特殊字符：`]` 与 `\`。
     * - `]` -> `\]`
     * - `\` -> `\\`
     */
    private fun escapeValue(v: String): String {
        if (v.isEmpty()) return ""
        val sb = StringBuilder(v.length)
        for (c in v) {
            when (c) {
                ']' -> sb.append("\\]")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}
