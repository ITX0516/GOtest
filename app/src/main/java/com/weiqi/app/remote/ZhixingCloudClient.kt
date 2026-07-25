package com.weiqi.app.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 智星云（ZhixingCloud）租机平台 REST 客户端。
 *
 * 封装实例的生命周期管理（创建/启动/停止/删除）与 GTP 接入点获取。
 * 所有阻塞网络调用均切换到 [Dispatchers.IO] 执行。
 *
 * 智星云官方 API 文档未公开，本实现中的路径与字段名均为合理推断，
 * 实际接入时需根据官方文档调整。
 *
 * @param authToken 用户 API token，放置于 `Authorization: Bearer <token>` 头。
 * @param httpClient 共享的 OkHttp 客户端（建议由上层注入带连接池/超时的实例）。
 * @param baseUrl API 根地址，默认 `https://api.zhixingcloud.com`。
 */
class ZhixingCloudClient(
    private val authToken: String,
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://api.zhixingcloud.com"
) {
    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private const val PLATFORM_NAME = "智星云"
        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)
    }

    /**
     * 列出当前账号下的全部实例。
     * @return 实例列表；接口返回空数组时为空列表。
     */
    suspend fun listInstances(): List<RemoteInstance> = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        val response = httpGet("/api/v1/instances")
        val body = response.body?.string() ?: "[]"
        val arr: JsonArray = json.parseToJsonElement(body).jsonArray
        arr.mapNotNull { runCatching { it.jsonObject.toRemoteInstance() }.getOrNull() }
    }

    /**
     * 创建一个新实例。
     * @param spec 实例规格。
     * @return 平台返回的实例信息（通常 status 为 PENDING/STARTING）。
     */
    suspend fun createInstance(spec: InstanceSpec): RemoteInstance = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径与字段名
        val payload = buildJsonObject {
            put("name", spec.name)
            put("gpu_type", spec.gpuType)
            put("hours", spec.hours)
            put("engine_type", spec.engineType)
            put("weights", spec.weightsName)
        }
        val response = httpPost("/api/v1/instances", payload)
        val body = response.body?.string() ?: "{}"
        json.parseToJsonElement(body).jsonObject.toRemoteInstance()
    }

    /**
     * 查询实例当前状态。
     * @param instanceId 实例 ID。
     * @return 状态枚举；无法识别的字符串归为 [InstanceStatus.ERROR]。
     */
    suspend fun getInstanceStatus(instanceId: String): InstanceStatus = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        val response = httpGet("/api/v1/instances/$instanceId/status")
        val body = response.body?.string() ?: "{}"
        val obj = json.parseToJsonElement(body).jsonObject
        val raw = obj["status"]?.jsonPrimitive?.content ?: "ERROR"
        parseStatus(raw)
    }

    /**
     * 启动已停止的实例。
     * @return 平台返回是否接受启动请求。
     */
    suspend fun startInstance(instanceId: String): Boolean = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        httpPostEmpty("/api/v1/instances/$instanceId/start").isSuccessful
    }

    /**
     * 停止运行中的实例。
     * @return 平台返回是否接受停止请求。
     */
    suspend fun stopInstance(instanceId: String): Boolean = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        httpPostEmpty("/api/v1/instances/$instanceId/stop").isSuccessful
    }

    /**
     * 销毁实例（不可恢复）。
     * @return 平台返回是否接受删除请求。
     */
    suspend fun deleteInstance(instanceId: String): Boolean = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        httpDelete("/api/v1/instances/$instanceId").isSuccessful
    }

    /**
     * 轮询等待实例进入 RUNNING 状态，使用指数退避（1s → 2s → 4s → ...，上限 10s）。
     *
     * @param instanceId 实例 ID。
     * @param timeoutMs 总超时（毫秒），默认 120s。
     * @return 进入 RUNNING 后的实例信息（含 GTP 接入点）。
     * @throws RemoteComputeException.InstanceNotReady 实例进入 ERROR/TERMINATED。
     * @throws RemoteComputeException.Timeout 超时仍未就绪。
     */
    suspend fun awaitRunning(instanceId: String, timeoutMs: Long = 120_000): RemoteInstance {
        val deadline = System.currentTimeMillis() + timeoutMs
        var delayMs = 1_000L
        while (System.currentTimeMillis() < deadline) {
            val status = getInstanceStatus(instanceId)
            when (status) {
                InstanceStatus.RUNNING -> return getInstance(instanceId)
                InstanceStatus.ERROR, InstanceStatus.TERMINATED ->
                    throw RemoteComputeException.InstanceNotReady(instanceId, status.name)
                else -> {
                    delay(delayMs)
                    delayMs = minOf(delayMs * 2, 10_000L)
                }
            }
        }
        throw RemoteComputeException.Timeout(timeoutMs)
    }

    /**
     * 获取实例的 GTP 接入点（host/port/password）。
     * @param instanceId 实例 ID。
     * @return GTP 接入点。
     * @throws RemoteComputeException.InstanceNotFound 实例不存在。
     */
    suspend fun getGtpEndpoint(instanceId: String): GtpEndpoint = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径与字段名
        val response = httpGet("/api/v1/instances/$instanceId/gtp-endpoint")
        val body = response.body?.string() ?: "{}"
        val obj = json.parseToJsonElement(body).jsonObject
        GtpEndpoint(
            host = obj["host"]?.jsonPrimitive?.content ?: "",
            port = obj["port"]?.jsonPrimitive?.intOrNull ?: 0,
            password = obj["password"]?.jsonPrimitive?.content ?: ""
        )
    }

    /** 获取实例完整信息。 */
    private suspend fun getInstance(instanceId: String): RemoteInstance = withContext(Dispatchers.IO) {
        // TODO: 实际接入时根据官方文档调整路径
        val response = httpGet("/api/v1/instances/$instanceId")
        val body = response.body?.string() ?: "{}"
        json.parseToJsonElement(body).jsonObject.toRemoteInstance()
    }

    // ====== HTTP 工具方法 ======

    private suspend fun httpGet(path: String) = execute(
        Request.Builder().url(joinUrl(path)).header("Authorization", "Bearer $authToken").get().build()
    )

    private suspend fun httpPost(path: String, payload: JsonObject) = execute(
        Request.Builder().url(joinUrl(path))
            .header("Authorization", "Bearer $authToken")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
    )

    private suspend fun httpPostEmpty(path: String) = execute(
        Request.Builder().url(joinUrl(path))
            .header("Authorization", "Bearer $authToken")
            .post(EMPTY_BODY)
            .build()
    )

    private suspend fun httpDelete(path: String) = execute(
        Request.Builder().url(joinUrl(path))
            .header("Authorization", "Bearer $authToken")
            .delete()
            .build()
    )

    private suspend fun execute(request: Request): okhttp3.Response {
        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                response.close()
                when (code) {
                    401 -> throw RemoteComputeException.AuthenticationFailed(PLATFORM_NAME)
                    404 -> throw RemoteComputeException.InstanceNotFound(
                        request.url.pathSegments.lastOrNull() ?: ""
                    )
                    else -> throw RemoteComputeException.ConnectionFailed(
                        request.url.host, request.url.port,
                        IllegalStateException("HTTP $code")
                    )
                }
            }
            return response
        } catch (e: RemoteComputeException) {
            throw e
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw RemoteComputeException.ConnectionFailed(
                request.url.host, request.url.port, e
            )
        }
    }

    private fun joinUrl(path: String): String =
        if (baseUrl.endsWith("/")) baseUrl + path.removePrefix("/") else "$baseUrl$path"

    /** 将平台 JSON 对象归一化为 [RemoteInstance]。 */
    private fun JsonObject.toRemoteInstance(): RemoteInstance {
        return RemoteInstance(
            id = this["id"]?.jsonPrimitive?.content ?: "",
            name = this["name"]?.jsonPrimitive?.content ?: "",
            status = parseStatus(this["status"]?.jsonPrimitive?.content ?: "ERROR"),
            gpu = this["gpu"]?.jsonPrimitive?.content
                ?: this["gpu_type"]?.jsonPrimitive?.content ?: "",
            cpu = this["cpu"]?.jsonPrimitive?.intOrNull
                ?: this["cpu_cores"]?.jsonPrimitive?.intOrNull ?: 0,
            memoryGb = this["memory_gb"]?.jsonPrimitive?.intOrNull
                ?: this["memory"]?.jsonPrimitive?.intOrNull ?: 0,
            pricePerHour = this["price_per_hour"]?.jsonPrimitive?.doubleOrNull
                ?: this["price"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            gtpEndpoint = (this["gtp_endpoint"] as? JsonObject)?.toGtpEndpoint()
        )
    }

    private fun JsonObject.toGtpEndpoint(): GtpEndpoint = GtpEndpoint(
        host = this["host"]?.jsonPrimitive?.content ?: "",
        port = this["port"]?.jsonPrimitive?.intOrNull ?: 0,
        password = this["password"]?.jsonPrimitive?.content ?: ""
    )

    private fun parseStatus(raw: String): InstanceStatus = when (raw.uppercase()) {
        "PENDING" -> InstanceStatus.PENDING
        "STARTING", "BOOTING", "CREATING" -> InstanceStatus.STARTING
        "RUNNING", "ACTIVE" -> InstanceStatus.RUNNING
        "STOPPING", "STOP" -> InstanceStatus.STOPPING
        "STOPPED", "PAUSED" -> InstanceStatus.STOPPED
        "TERMINATED", "DELETED", "DESTROYED" -> InstanceStatus.TERMINATED
        else -> InstanceStatus.ERROR
    }
}
