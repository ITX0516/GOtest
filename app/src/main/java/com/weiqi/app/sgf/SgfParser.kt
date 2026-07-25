package com.weiqi.app.sgf

/**
 * 递归下降 SGF 解析器。
 *
 * 支持 FF[4] 子集：
 * - 嵌套括号的多局集合与变着（GameTree -> "(" Sequence GameTree* ")"）
 * - 属性值列表，如 `AB[ab][cd][ef]`
 * - 转义字符：`\]` 保留为 `]`、`\\` 保留为 `\`，行尾 `\` 作为软换行忽略
 * - 空方括号 `[]` 表示 pass 或空值
 *
 * SGF 坐标约定：`a`=0、`b`=1、……、`s`=18。
 */
object SgfParser {
    /**
     * 解析 SGF 文本，返回第一局游戏树。
     * @throws IllegalArgumentException 文本不含任何游戏树时抛出。
     */
    fun parse(text: String): SgfGameTree {
        val trees = parseCollection(text)
        if (trees.isEmpty()) {
            throw IllegalArgumentException("SGF 文本中未找到任何游戏树")
        }
        return trees.first()
    }

    /**
     * 解析 SGF 集合，一个文件可包含多局。
     * @return 游戏树列表，按文件中出现顺序排列。
     */
    fun parseCollection(text: String): List<SgfGameTree> {
        val parser = Parser(text)
        return parser.parseCollection()
    }

    /**
     * 内部递归下降解析器实现。
     * 维护一个只读的源字符串和当前解析位置 [pos]。
     */
    private class Parser(val src: String) {
        private var pos = 0
        private val len = src.length

        /** 顶层：解析多个 GameTree，跳过非括号字符。 */
        fun parseCollection(): List<SgfGameTree> {
            val trees = mutableListOf<SgfGameTree>()
            skipBom()
            skipWhitespace()
            while (pos < len) {
                if (src[pos] == '(') {
                    val tree = parseGameTree()
                    if (tree != null) trees.add(tree)
                } else {
                    // 容错：跳过非预期字符（注释、空白、BOM 等）
                    pos++
                }
                skipWhitespace()
            }
            return trees
        }

        /**
         * 解析一个 GameTree: "(" Sequence GameTree* ")"。
         * 子树统一挂在 Sequence 的最后一个节点上（符合 SGF 语义）。
         */
        private fun parseGameTree(): SgfGameTree? {
            expect('(')
            skipWhitespace()
            // 容错：空树 ()
            if (pos < len && src[pos] == ')') {
                pos++
                return null
            }
            val nodes = parseSequence()
            skipWhitespace()
            // 解析嵌套子树（变着）
            val subTrees = mutableListOf<SgfGameTree>()
            while (pos < len && src[pos] == '(') {
                val sub = parseGameTree()
                if (sub != null) subTrees.add(sub)
                skipWhitespace()
            }
            expect(')')
            if (nodes.isEmpty()) return null

            // 将 Sequence 中的节点串联成主干
            val root = nodes.first()
            var current = root
            for (i in 1 until nodes.size) {
                current = current.addChild(nodes[i])
            }
            // 子树作为最后一个节点的子分支
            val lastNode = nodes.last()
            for (sub in subTrees) {
                lastNode.addChild(sub.root)
            }
            return SgfGameTree(root)
        }

        /** 解析 Sequence: Node+（每个 Node 以 ';' 开头）。 */
        private fun parseSequence(): List<SgfNode> {
            val nodes = mutableListOf<SgfNode>()
            skipWhitespace()
            while (pos < len && src[pos] == ';') {
                pos++ // 消费 ';'
                nodes.add(parseNode())
                skipWhitespace()
            }
            return nodes
        }

        /** 解析单个 Node: ; Property*。 */
        private fun parseNode(): SgfNode {
            val node = SgfNode()
            skipWhitespace()
            while (pos < len) {
                val c = src[pos]
                if (c == ';' || c == '(' || c == ')') break
                if (isPropIdentStart(c)) {
                    parseProperty(node)
                    skipWhitespace()
                } else if (c.isWhitespace()) {
                    pos++
                } else {
                    // 容错：跳过未识别字符
                    pos++
                }
            }
            return node
        }

        /** 解析 Property: PropIdent PropValue+，同名属性值合并。 */
        private fun parseProperty(node: SgfNode) {
            val ident = parsePropIdent()
            if (ident.isEmpty()) return
            skipWhitespace()
            val values = mutableListOf<String>()
            while (pos < len && src[pos] == '[') {
                values.add(parsePropValue())
                skipWhitespace()
            }
            if (values.isEmpty()) return
            // 多值属性合并：兼容 AB[ab][cd] 与 AB[ab]AB[cd] 两种写法
            val existing = node.properties[ident]
            node.properties[ident] = if (existing == null) values.toList() else existing + values
        }

        /** 解析 PropIdent：连续字母（兼容大小写）。 */
        private fun parsePropIdent(): String {
            val start = pos
            while (pos < len) {
                val c = src[pos]
                if (c in 'A'..'Z' || c in 'a'..'z') {
                    pos++
                } else {
                    break
                }
            }
            return src.substring(start, pos)
        }

        /**
         * 解析 PropValue: "[" ... "]"。
         * 处理转义：`\]` -> `]`，`\\` -> `\`，行尾 `\` 视为软换行（忽略）。
         * 其他字符按原样保留（含换行、中文等）。
         */
        private fun parsePropValue(): String {
            expect('[')
            val sb = StringBuilder()
            while (pos < len) {
                val c = src[pos]
                if (c == '\\') {
                    pos++
                    if (pos < len) {
                        val n = src[pos]
                        if (n == '\n' || n == '\r') {
                            // 软换行：丢弃反斜杠与换行
                            pos++
                            if (n == '\r' && pos < len && src[pos] == '\n') pos++
                        } else {
                            // 转义字符原样保留（去掉反斜杠）
                            sb.append(n)
                            pos++
                        }
                    }
                } else if (c == ']') {
                    pos++
                    break
                } else {
                    sb.append(c)
                    pos++
                }
            }
            return sb.toString()
        }

        private fun isPropIdentStart(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z'

        /** 期望当前位置为 [c]，否则抛出异常；消费该字符。 */
        private fun expect(c: Char) {
            if (pos >= len || src[pos] != c) {
                val found = if (pos < len) src[pos].toString() else "EOF"
                throw IllegalArgumentException("期望 '$c'，但在位置 $pos 找到 '$found'")
            }
            pos++
        }

        private fun skipWhitespace() {
            while (pos < len && src[pos].isWhitespace()) pos++
        }

        /** 跳过 UTF-8 BOM（\uFEFF）。 */
        private fun skipBom() {
            if (len > 0 && src[0] == '\uFEFF') pos = 1
        }
    }
}
