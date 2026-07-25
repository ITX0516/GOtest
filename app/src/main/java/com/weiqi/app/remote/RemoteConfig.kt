package com.weiqi.app.remote

/**
 * 远程计算连接配置。
 *
 * 描述如何接入一个远程围棋引擎：直连个人 PC（[RemotePlatform.CUSTOM_PC]）、
 * 通过 SSH 隧道（[RemotePlatform.SSH_TUNNEL]），或经由云租机平台
 * （[RemotePlatform.ZHIXING_CLOUD] / [RemotePlatform.SUANYUN]）拉起实例后再连接。
 *
 * @property host GTP 服务主机。云平台场景下由 [RemoteEngine] 在启动实例后填充。
 * @property port GTP 服务端口，默认 8080。
 * @property username 可选用户名（部分 SSH/认证场景使用）。
 * @property password GTP 服务密码（部分 GTP 服务端支持 `auth` 命令）。
 * @property platform 接入平台类型，决定 [RemoteEngine] 的启动流程。
 * @property useTls 是否启用 TLS（当前预留，GTP over TLS 暂未实现）。
 * @property authToken 云平台 API token，放置于 HTTP Header。
 * @property instanceId 已有实例 ID；为空时 [RemoteEngine] 会按 [enginePath]/[weightsPath] 创建新实例。
 * @property enginePath 引擎可执行文件路径/名称（如 "katago"），用于创建实例时指定。
 * @property weightsPath 权重文件名称（如 "b18c384"），用于创建实例时指定。
 * @property connectTimeoutMs TCP 连接超时（毫秒）。
 * @property readTimeoutMs 单次 GTP 读取超时（毫秒），分析类长命令应留足时间。
 */
data class RemoteConfig(
    val host: String,
    val port: Int = 8080,
    val username: String = "",
    val password: String = "",
    val platform: RemotePlatform,
    val useTls: Boolean = false,
    val authToken: String = "",
    val instanceId: String = "",
    val enginePath: String = "",
    val weightsPath: String = "",
    val connectTimeoutMs: Int = 15_000,
    val readTimeoutMs: Int = 300_000
)

/**
 * 远程计算接入平台类型。
 *
 * @property displayName 中文显示名称，用于 UI 展示与异常信息。
 */
enum class RemotePlatform(val displayName: String) {
    /** 智星云租机平台。 */
    ZHIXING_CLOUD("智星云"),

    /** 算云租机平台。 */
    SUANYUN("算云"),

    /** 个人 PC 直连（用户提供 host:port）。 */
    CUSTOM_PC("个人PC"),

    /** SSH 隧道接入（用户需自行建立隧道，本端按本地端口连接）。 */
    SSH_TUNNEL("SSH隧道");

    companion object {
        /**
         * 由字符串名称解析为 [RemotePlatform]，匹配大小写不敏感。
         * @param s 平台名称（枚举常量名或 displayName）。
         * @return 匹配到的平台；无法识别返回 null。
         */
        fun fromName(s: String): RemotePlatform? {
            if (s.isBlank()) return null
            val upper = s.trim().uppercase()
            values().forEach { if (it.name.uppercase() == upper) return it }
            values().forEach { if (it.displayName == s.trim()) return it }
            return null
        }
    }
}

/**
 * 远程引擎实例的运行状态。
 *
 * 状态流转大致为：
 * PENDING → STARTING → RUNNING ⇄ STOPPING → STOPPED → TERMINATED；
 * 任意阶段异常进入 ERROR。
 */
enum class InstanceStatus {
    /** 已创建但尚未启动。 */
    PENDING,
    /** 启动中（拉镜像/初始化）。 */
    STARTING,
    /** 运行中，可接受 GTP 连接。 */
    RUNNING,
    /** 停止中。 */
    STOPPING,
    /** 已停止，可再次启动。 */
    STOPPED,
    /** 已销毁，不可恢复。 */
    TERMINATED,
    /** 异常状态。 */
    ERROR
}

/**
 * GTP 服务接入点。
 *
 * 由云平台在实例进入 RUNNING 后返回，或由用户在 [RemoteConfig] 中直接配置。
 *
 * @property host GTP 主机名/IP。
 * @property port GTP 端口。
 * @property password 可选密码（部分服务端需 `auth` 认证）。
 */
data class GtpEndpoint(val host: String, val port: Int, val password: String = "")

/**
 * 创建实例的规格参数。
 *
 * @property name 实例显示名称。
 * @property gpuType GPU 型号标识，如 "RTX3090"、"V100"。
 * @property hours 租用时长（小时）。
 * @property engineType 引擎类型，默认 "katago"。
 * @property weightsName 权重文件名，默认 "b18c384"。
 */
data class InstanceSpec(
    val name: String,
    val gpuType: String,
    val hours: Int = 1,
    val engineType: String = "katago",
    val weightsName: String = "b18c384"
)

/**
 * 远程引擎实例的统一描述。
 *
 * 由 [ZhixingCloudClient] / [SuanyunClient] 从各自平台的 JSON 响应归一化而来。
 *
 * @property id 实例唯一 ID。
 * @property name 实例名称。
 * @property status 当前运行状态。
 * @property gpu GPU 型号描述。
 * @property cpu CPU 核数。
 * @property memoryGb 内存（GB）。
 * @property pricePerHour 每小时价格（元）。
 * @property gtpEndpoint GTP 接入点；仅当 [status] 为 [InstanceStatus.RUNNING] 时可能非空。
 */
data class RemoteInstance(
    val id: String,
    val name: String,
    val status: InstanceStatus,
    val gpu: String,
    val cpu: Int,
    val memoryGb: Int,
    val pricePerHour: Double,
    val gtpEndpoint: GtpEndpoint? = null
)
