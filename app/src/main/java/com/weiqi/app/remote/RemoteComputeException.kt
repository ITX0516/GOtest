package com.weiqi.app.remote

/**
 * 远程计算模块的统一异常基类。
 *
 * 所有远程计算相关的错误（连接失败、认证失败、GTP 错误等）都封装为此密封类的子类，
 * 便于调用方使用 `when` 表达式做穷尽性匹配，避免遗漏错误分支。
 *
 * @param msg 异常描述信息
 * @param cause 原始异常（可选），用于保留完整的异常栈
 */
sealed class RemoteComputeException(msg: String, cause: Throwable? = null) : Exception(msg, cause) {

    /**
     * 无法建立到远程主机的网络连接。
     *
     * @param host 目标主机
     * @param port 目标端口
     * @param cause 原始 IO 异常
     */
    class ConnectionFailed(host: String, port: Int, cause: Throwable? = null) :
        RemoteComputeException("无法连接到 $host:$port", cause)

    /**
     * 平台认证失败（token 或密码错误，HTTP 401）。
     *
     * @param platform 平台显示名称，如 "智星云"、"算云"
     */
    class AuthenticationFailed(platform: String) :
        RemoteComputeException("$platform 认证失败，请检查 token/密码")

    /**
     * 实例状态异常，无法用于 GTP 通信（如长期处于 STARTING、ERROR 等）。
     *
     * @param instanceId 实例 ID
     * @param status 当前状态字符串
     */
    class InstanceNotReady(instanceId: String, status: String) :
        RemoteComputeException("实例 $instanceId 状态异常: $status")

    /**
     * GTP 协议层错误：引擎返回了 `?` 开头的错误响应。
     *
     * @param command 触发错误的 GTP 命令
     * @param response 引擎返回的错误内容
     */
    class GtpError(command: String, response: String) :
        RemoteComputeException("GTP 命令 '$command' 失败: $response")

    /**
     * 操作超时（连接超时、读取超时、等待实例就绪超时等）。
     *
     * @param timeoutMs 超时阈值（毫秒）
     */
    class Timeout(timeoutMs: Long) :
        RemoteComputeException("操作超时 (${timeoutMs}ms)")

    /**
     * 指定的实例在平台上不存在（HTTP 404）。
     *
     * @param instanceId 实例 ID
     */
    class InstanceNotFound(instanceId: String) :
        RemoteComputeException("找不到实例: $instanceId")
}
