package com.weiqi.app.engine.jni

/**
 * Kotlin ↔ C++ JNI 桥接对象。
 *
 * 由 [com.weiqi.app.engine.KataGoEngine] / [com.weiqi.app.engine.LeelaZeroEngine] 使用，
 * 通过 native handle（Long）持有底层 [weiqi::GtpEngine] 实例的指针。
 *
 * native 库：`libweiqi_engine.so`，由 `app/src/main/cpp/CMakeLists.txt` 构建。
 * 未链接真实引擎时构建为 stub 实现，genmove 返回随机合法点或 pass，用于 UI 调试。
 *
 * 引擎类型常量：
 * - [ENGINE_KATAGO] = 1
 * - [ENGINE_LEELAZERO] = 2
 */
object NativeEngineBridge {

    init {
        // 加载 native 引擎库；加载失败会抛 UnsatisfiedLinkError，
        // 由上层（如 EngineManager）捕获并提示用户。
        System.loadLibrary("weiqi_engine")
    }

    /** KataGo 引擎类型标识。 */
    const val ENGINE_KATAGO = 1

    /** LeelaZero 引擎类型标识。 */
    const val ENGINE_LEELAZERO = 2

    // ===== 回调注册表（用于流式分析） =====
    // callbackId -> 输出回调。C++ 通过 onAnalysisUpdate 反向调用 Kotlin。
    private val analysisCallbacks = mutableMapOf<Int, (String) -> Unit>()
    private val callbackLock = Any()
    private val callbackIdCounter = java.util.concurrent.atomic.AtomicInteger(1)

    /**
     * 注册一个流式分析回调，返回唯一的 callbackId。
     * 该 id 应传给 [startAnalysis]。
     */
    internal fun registerAnalysisCallback(callback: (String) -> Unit): Int {
        val id = callbackIdCounter.getAndIncrement()
        synchronized(callbackLock) { analysisCallbacks[id] = callback }
        return id
    }

    /** 注销回调。 */
    internal fun unregisterAnalysisCallback(callbackId: Int) {
        synchronized(callbackLock) { analysisCallbacks.remove(callbackId) }
    }

    /**
     * 由 C++ 通过 JNI 调用，把分析输出回传到 Kotlin。
     * 必须为 `@JvmStatic` 以便 JNI 查找静态方法。
     */
    @JvmStatic
    private fun onAnalysisUpdate(callbackId: Int, line: String) {
        val cb = synchronized(callbackLock) { analysisCallbacks[callbackId] } ?: return
        try {
            cb.invoke(line)
        } catch (_: Throwable) {
            // 回调异常不应影响 native 线程
        }
    }

    // ===== Native 方法 =====

    /**
     * 创建一个 native 引擎实例。
     * @param engineType [ENGINE_KATAGO] 或 [ENGINE_LEELAZERO]。
     * @param configJson 引擎配置 JSON（见 [com.weiqi.app.engine.EngineConfig.toJson]）。
     * @return 引擎句柄（指向 native 对象的指针）；0 表示创建失败。
     */
    fun createEngine(engineType: Int, configJson: String): Long =
        nativeCreateEngine(engineType, configJson)

    /**
     * 销毁 native 引擎实例并释放资源。
     * @param handle [createEngine] 返回的句柄。
     */
    fun destroyEngine(handle: Long) {
        if (handle != 0L) nativeDestroyEngine(handle)
    }

    /**
     * 发送一条 GTP 命令并同步等待响应。
     * @param handle 引擎句柄。
     * @param command GTP 命令字符串（不带换行）。
     * @return GTP 响应正文（已去除前缀 `=` / `?` 与结尾换行）；
     *         失败时返回以 `error:` 开头的字符串。
     */
    fun sendGtpCommand(handle: Long, command: String): String =
        nativeSendGtpCommand(handle, command)

    /**
     * 启动流式分析。native 端会在后台线程产出分析输出，
     * 通过 [onAnalysisUpdate] 回调到 Kotlin（与 [callbackId] 关联）。
     * @param handle 引擎句柄。
     * @param command `kata-analyze` / `lz-analyze` 命令字符串。
     * @param callbackId 由 [registerAnalysisCallback] 返回的 id。
     */
    fun startAnalysis(handle: Long, command: String, callbackId: Int) {
        nativeStartAnalysis(handle, command, callbackId)
    }

    /** 停止流式分析。 */
    fun stopAnalysis(handle: Long) {
        nativeStopAnalysis(handle)
    }

    /** 检查指定类型的引擎是否在当前设备可用（已编译/可加载）。 */
    fun isEngineAvailable(engineType: Int): Boolean = nativeIsEngineAvailable(engineType)

    /** 获取指定类型引擎的版本字符串。 */
    fun getEngineVersion(engineType: Int): String = nativeGetEngineVersion(engineType)

    // ===== native 声明 =====
    @JvmStatic private external fun nativeCreateEngine(engineType: Int, configJson: String): Long
    @JvmStatic private external fun nativeDestroyEngine(handle: Long)
    @JvmStatic private external fun nativeSendGtpCommand(handle: Long, command: String): String
    @JvmStatic private external fun nativeStartAnalysis(handle: Long, command: String, callbackId: Int)
    @JvmStatic private external fun nativeStopAnalysis(handle: Long)
    @JvmStatic private external fun nativeIsEngineAvailable(engineType: Int): Boolean
    @JvmStatic private external fun nativeGetEngineVersion(engineType: Int): String
}
