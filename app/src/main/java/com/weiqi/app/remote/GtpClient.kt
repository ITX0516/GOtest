package com.weiqi.app.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets

/**
 * 基于 TCP Socket 的 GTP（Go Text Protocol）客户端。
 *
 * 通过阻塞式 Java [Socket] 与远程 GTP 引擎（如 KataGo）通信，所有阻塞调用均
 * 切换到 [Dispatchers.IO] 执行，避免阻塞协程调度器。
 *
 * GTP 响应格式约定：
 * - 第一行以 `=`（成功）或 `?`（错误）开头，后跟可选空格与响应内容；
 * - 多行响应在首行之后继续输出；
 * - 以一个空行（仅 `\n`）作为响应终止符；
 * - 流式分析命令（如 `lz-analyze` / `kata-analyze`）在 `=` 行之后持续输出数据行，
 *   直到被下一条命令打断或引擎自行结束。
 *
 * @param host GTP 服务主机。
 * @param port GTP 服务端口。
 * @param password 可选密码；非空时连接后发送 `auth <password>` 进行认证。
 * @param connectTimeoutMs TCP 连接超时（毫秒）。
 * @param readTimeoutMs 单次读取超时（毫秒），对应 `Socket.setSoTimeout`。
 */
class GtpClient(
    private val host: String,
    private val port: Int,
    private val password: String = "",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 300_000
) {
    /** 底层 socket，连接前为 null。 */
    @Volatile
    private var socket: Socket? = null

    /** 读取缓冲，连接后非空。 */
    @Volatile
    private var reader: BufferedReader? = null

    /** 写入缓冲，连接后非空。 */
    @Volatile
    private var writer: BufferedWriter? = null

    /** 串行化 [send] 调用，保证 GTP 请求/响应配对，避免交错。 */
    private val sendMutex = Mutex()

    /** 流式分析任务引用，用于 [stopAnalyzeStream] 主动打断。 */
    @Volatile
    private var streamJob: Job? = null

    /**
     * 当前是否处于已连接状态。
     * 注：仅检查 socket 是否打开，不保证对端存活，需要时请用 [ping] 探活。
     */
    val isConnected: Boolean
        get() = socket?.let { it.isConnected && !it.isClosed } == true

    /**
     * 建立 TCP 连接并完成可选的密码认证。
     *
     * @throws RemoteComputeException.ConnectionFailed 连接失败（含超时、IO 错误）。
     * @throws RemoteComputeException.AuthenticationFailed 密码认证失败。
     */
    suspend fun connect() = withContext(Dispatchers.IO) {
        if (isConnected) return@withContext
        try {
            val s = Socket()
            s.soTimeout = readTimeoutMs
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            reader = BufferedReader(InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))
            writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))

            // 部分 GTP 服务端（如 KataGo 的 gtp 三方封装）支持 auth 命令做密码校验
            if (password.isNotEmpty()) {
                try {
                    send("auth $password")
                } catch (e: RemoteComputeException.GtpError) {
                    closeQuietly()
                    throw RemoteComputeException.AuthenticationFailed("GTP")
                }
            }
        } catch (e: RemoteComputeException) {
            closeQuietly()
            throw e
        } catch (e: CancellationException) {
            // 协程取消必须原样抛出，不能包装，否则破坏取消语义；仍需释放已建资源避免泄露
            closeQuietly()
            throw e
        } catch (e: Exception) {
            closeQuietly()
            throw RemoteComputeException.ConnectionFailed(host, port, e)
        }
    }

    /**
     * 主动断开连接，释放 socket 与读写流。可重复调用。
     */
    suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeQuietly()
    }

    /**
     * 发送一条 GTP 命令并等待完整响应。
     *
     * 内部通过 [sendMutex] 串行化，保证多个协程并发调用时响应不会错乱。
     *
     * @param command GTP 命令行（不含换行符），如 `"genmove B"`。
     * @return 响应正文（去掉首行 `=`/`?` 前缀与末尾空行后的全部内容）。
     * @throws RemoteComputeException.GtpError 引擎返回 `?` 错误响应。
     * @throws RemoteComputeException.Timeout 读取超时。
     * @throws RemoteComputeException.ConnectionFailed 连接已断开或 IO 异常。
     */
    suspend fun send(command: String): String = sendMutex.withLock {
        withContext(Dispatchers.IO) {
            val w = writer ?: throw RemoteComputeException.ConnectionFailed(host, port)
            val r = reader ?: throw RemoteComputeException.ConnectionFailed(host, port)
            try {
                w.write(command)
                w.write("\n")
                w.flush()

                // 读取首行（状态行）
                val firstLine = r.readLine()
                    ?: throw RemoteComputeException.ConnectionFailed(host, port)

                val isError = firstLine.startsWith("?")
                val isSuccess = firstLine.startsWith("=")
                if (!isError && !isSuccess) {
                    // 不符合 GTP 协议的响应
                    throw RemoteComputeException.GtpError(command, firstLine)
                }

                // 首行去掉状态字符与可选空格
                val firstContent = firstLine.drop(1).let { if (it.startsWith(" ")) it.drop(1) else it }
                val lines = ArrayList<String>()
                if (firstContent.isNotEmpty()) lines.add(firstContent)

                // 持续读取直到空行（响应终止符）
                while (true) {
                    val line = r.readLine()
                        ?: throw RemoteComputeException.ConnectionFailed(host, port)
                    if (line.isEmpty()) break
                    lines.add(line)
                }

                val response = lines.joinToString("\n")
                if (isError) {
                    throw RemoteComputeException.GtpError(command, response)
                }
                response
            } catch (e: RemoteComputeException) {
                throw e
            } catch (e: SocketTimeoutException) {
                throw RemoteComputeException.Timeout(readTimeoutMs.toLong())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw RemoteComputeException.ConnectionFailed(host, port, e)
            }
        }
    }

    /**
     * 以流式方式执行分析命令（如 `lz-analyze` / `kata-analyze`），每收到一行输出就 emit。
     *
     * 实现使用 [callbackFlow] 包装：启动一个 IO 协程持续读取 socket，逐行 trySend；
     * 当下游取消（cancel/collect 完成）时，通过发送一个换行符打断引擎的分析循环。
     *
     * 注意：调用期间不应并发调用 [send]，否则会破坏响应流。
     *
     * @param command 流式分析命令，如 `"lz-analyze B 100"`。
     * @return 逐行输出的分析数据流；引擎输出 `?` 错误行时以 [RemoteComputeException.GtpError] 关闭。
     */
    fun analyzeStream(command: String): Flow<String> = callbackFlow {
        val r = reader
        val w = writer
        if (r == null || w == null) {
            close(RemoteComputeException.ConnectionFailed(host, port))
            return@callbackFlow
        }

        // 发送命令（非阻塞写入，失败立即关闭流）
        try {
            synchronized(w) {
                w.write(command)
                w.write("\n")
                w.flush()
            }
        } catch (e: Exception) {
            close(RemoteComputeException.ConnectionFailed(host, port, e))
            return@callbackFlow
        }

        val job = launch(Dispatchers.IO) {
            try {
                // 读取状态行（= 或 ?）
                val statusLine = r.readLine()
                if (statusLine == null) {
                    close(RemoteComputeException.ConnectionFailed(host, port))
                    return@launch
                }
                if (statusLine.startsWith("?")) {
                    close(RemoteComputeException.GtpError(command, statusLine.drop(1).trim()))
                    return@launch
                }
                // 状态行 "=" 后可能直接带内容（部分实现）
                val head = statusLine.drop(1).let { if (it.startsWith(" ")) it.drop(1) else it }
                if (head.isNotEmpty()) {
                    trySend(head)
                }
                // 持续读取分析数据行
                while (isActive) {
                    val line = r.readLine() ?: break
                    if (line.isEmpty()) {
                        // 空行视为本次快照结束；部分引擎在 maxVisits 达成后会以空行收尾
                        break
                    }
                    if (line.startsWith("=") || line.startsWith("?")) {
                        // 不应出现在流中；防御性跳出
                        break
                    }
                    trySend(line)
                }
            } catch (e: SocketTimeoutException) {
                // 读超时：分析流长时间无输出，正常结束流（不作为错误）
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                close(RemoteComputeException.ConnectionFailed(host, port, e))
            }
        }
        streamJob = job

        awaitClose {
            job.cancel()
            // 打断引擎分析：发送换行符，多数 GTP 实现会终止当前 analyze 并回到命令提示
            try {
                synchronized(w) {
                    w.write("\n")
                    w.flush()
                }
            } catch (_: Exception) {
                // 打断失败忽略，连接可能已断
            }
        }
    }

    /**
     * 主动停止当前流式分析（如有），通过取消流任务实现。
     */
    fun stopAnalyzeStream() {
        streamJob?.cancel()
        streamJob = null
    }

    /**
     * 探活：发送 `protocol_version` 命令，成功即认为连接存活。
     * @return 连接存活返回 true；任何异常返回 false。
     */
    suspend fun ping(): Boolean = try {
        send("protocol_version")
        true
    } catch (_: Exception) {
        false
    }

    /** 关闭读写流与 socket，吞掉所有异常。 */
    private fun closeQuietly() {
        streamJob?.cancel()
        streamJob = null
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        socket = null
    }
}
