package com.weiqi.app.core

/**
 * 棋盘的一行/一列数据，内部以 [IntArray] 存储，对外暴露 [Stone] 类型访问。
 *
 * 设计目的：在保持 [Board] 内部存储紧凑（Int）的同时，
 * 对外提供类型安全的 [Stone] 读写接口。
 *
 * @param size 该数组的长度。
 */
class StoneArray(val size: Int) {
    private val data: IntArray = IntArray(size)

    /** 读取第 i 个位置的棋子。 */
    operator fun get(i: Int): Stone = when (data[i]) {
        1 -> Stone.BLACK
        2 -> Stone.WHITE
        else -> Stone.EMPTY
    }

    /** 设置第 i 个位置的棋子。 */
    operator fun set(i: Int, s: Stone) {
        data[i] = s.value
    }

    /** 创建一份深拷贝。 */
    fun copy(): StoneArray {
        val c = StoneArray(size)
        for (i in 0 until size) c.data[i] = data[i]
        return c
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is StoneArray) return false
        if (size != other.size) return false
        return data.contentEquals(other.data)
    }

    override fun hashCode(): Int = data.contentHashCode()
}

/**
 * 围棋棋盘状态。纯数据结构，不包含规则判定逻辑。
 *
 * 内部使用 [Array]<[StoneArray]> 存储 size×size 的棋子分布，
 * 其中 stones[x][y] 表示坐标 (x,y) 处的棋子。
 * 坐标约定同 [Vertex]：(0,0) 在左上角，x 向右、y 向下。
 *
 * 注意：本类内部数组是可变的；对外应通过 [copy] 复制后再修改，
 * 以保持"逻辑不可变"的语义。
 *
 * @param size 棋盘路数，默认 [Vertex.DEFAULT_SIZE]（19）。
 */
class Board(val size: Int = Vertex.DEFAULT_SIZE) {
    /** size×size 的棋子数组，stones[x][y] 表示 (x,y) 处的棋子。 */
    val stones: Array<StoneArray> = Array(size) { StoneArray(size) }

    /** 读取 (x,y) 处的棋子；越界返回 [Stone.EMPTY]。 */
    operator fun get(x: Int, y: Int): Stone {
        if (!inBounds(x, y)) return Stone.EMPTY
        return stones[x][y]
    }

    /** 读取 [v] 处的棋子。 */
    operator fun get(v: Vertex): Stone = get(v.x, v.y)

    /** 设置 (x,y) 处的棋子；越界则忽略。 */
    fun set(x: Int, y: Int, s: Stone) {
        if (inBounds(x, y)) stones[x][y] = s
    }

    /** 判断坐标是否在棋盘范围内。 */
    fun inBounds(x: Int, y: Int): Boolean = x in 0 until size && y in 0 until size

    /** 判断坐标是否在棋盘范围内。 */
    fun inBounds(v: Vertex): Boolean = inBounds(v.x, v.y)

    /** 创建一份深拷贝棋盘。 */
    fun copy(): Board {
        val b = Board(size)
        for (i in 0 until size) {
            b.stones[i] = stones[i].copy()
        }
        return b
    }

    /** 统计棋盘上指定颜色棋子的数量。 */
    fun stoneCount(color: Stone): Int {
        var count = 0
        for (i in 0 until size) {
            for (j in 0 until size) {
                if (stones[i][j] == color) count++
            }
        }
        return count
    }

    /** 返回 (x,y) 的正交邻接点（不超过棋盘边界）。 */
    fun neighbors(x: Int, y: Int): List<Vertex> {
        val result = ArrayList<Vertex>(4)
        if (x > 0) result.add(Vertex(x - 1, y))
        if (x < size - 1) result.add(Vertex(x + 1, y))
        if (y > 0) result.add(Vertex(x, y - 1))
        if (y < size - 1) result.add(Vertex(x, y + 1))
        return result
    }

    /** 返回 [v] 的正交邻接点。 */
    fun neighbors(v: Vertex): List<Vertex> = neighbors(v.x, v.y)

    /**
     * 获取与 (x,y) 同色连通的整群棋子（含 (x,y) 本身）。
     * 若 (x,y) 为空点，返回空列表。
     */
    fun getGroup(x: Int, y: Int): List<Vertex> {
        val color = get(x, y)
        if (color == Stone.EMPTY) return emptyList()
        val visited = mutableSetOf<Vertex>()
        val stack = ArrayDeque<Vertex>()
        val start = Vertex(x, y)
        stack.addLast(start)
        visited.add(start)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            for (n in neighbors(cur)) {
                if (get(n) == color && n !in visited) {
                    visited.add(n)
                    stack.addLast(n)
                }
            }
        }
        return visited.toList()
    }

    /**
     * 计算群（一组同色棋子）的气数。
     * @param group 由 [getGroup] 返回的同色连通群。
     * @return 不同空邻点的数量。
     */
    fun getLiberties(group: List<Vertex>): Int {
        val liberties = mutableSetOf<Vertex>()
        for (v in group) {
            for (n in neighbors(v)) {
                if (get(n) == Stone.EMPTY) liberties.add(n)
            }
        }
        return liberties.size
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Board) return false
        if (size != other.size) return false
        for (i in 0 until size) {
            if (stones[i] != other.stones[i]) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = size
        for (i in 0 until size) h = 31 * h + stones[i].hashCode()
        return h
    }
}
