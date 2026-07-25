package com.weiqi.app.engine

/**
 * 引擎配置。
 *
 * 适用于本地引擎（KataGo / LeelaZero）与远程引擎（REMOTE）。
 * 远程引擎使用 [remoteHost]/[remotePort]/[remotePassword]/[remotePlatform]，
 * 本地引擎使用 [weightsPath]/[threads]/[maxVisits] 等。
 *
 * PROCESS 模式（子进程）额外字段：[executablePath]、[configPath]、[workingDir]
 * DYLIB 模式（动态库）额外字段：[libPath]
 *
 * @param type 引擎类型。
 * @param weightsPath 权重文件绝对路径（本地引擎）。
 * @param threads 计算线程数。
 * @param maxVisits 单次思考最大访问数。
 * @param komi 贴目。
 * @param boardSize 棋盘路数。
 * @param cpuOnly 是否仅使用 CPU（不使用 GPU/NNAPI）。
 * @param enablePonder 是否启用后台 ponder。
 * @param executablePath PROCESS 模式：引擎可执行文件绝对路径。
 * @param configPath KataGo 可选配置文件 .cfg 绝对路径。
 * @param workingDir 引擎工作目录（PROCESS 模式默认为 filesDir/bin/）。
 * @param libPath DYLIB 模式：引擎 .so 路径（默认 libkatago.so / libleelaz.so）。
 * @param remoteHost 远程引擎主机。
 * @param remotePort 远程引擎端口。
 * @param remotePassword 远程引擎访问密码。
 * @param remotePlatform 远程引擎平台标识（如 "katago" / "leelazero"）。
 */
data class EngineConfig(
    val type: EngineType,
    val weightsPath: String,
    val threads: Int = 2,
    val maxVisits: Int = 800,
    val komi: Double = 7.5,
    val boardSize: Int = 19,
    val cpuOnly: Boolean = false,
    val enablePonder: Boolean = false,
    val executablePath: String = "",
    val configPath: String = "",
    val workingDir: String = "",
    val libPath: String = "",
    val remoteHost: String = "",
    val remotePort: Int = 0,
    val remotePassword: String = "",
    val remotePlatform: String = ""
) {

    /**
     * 序列化为紧凑 JSON 字符串，用于通过 JNI 传递给 native 引擎。
     * 字符串值会做基本的转义处理。
     */
    fun toJson(): String {
        fun esc(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
        val typeStr = when (type) {
            EngineType.KATAGO -> "katago"
            EngineType.LEELAZERO -> "leelazero"
            EngineType.REMOTE -> "remote"
        }
        return buildString {
            append('{')
            append("\"type\":\"").append(typeStr).append('"')
            append(",\"weightsPath\":\"").append(esc(weightsPath)).append('"')
            append(",\"threads\":").append(threads)
            append(",\"maxVisits\":").append(maxVisits)
            append(",\"komi\":").append(komi)
            append(",\"boardSize\":").append(boardSize)
            append(",\"cpuOnly\":").append(cpuOnly)
            append(",\"enablePonder\":").append(enablePonder)
            append(",\"executablePath\":\"").append(esc(executablePath)).append('"')
            append(",\"configPath\":\"").append(esc(configPath)).append('"')
            append(",\"workingDir\":\"").append(esc(workingDir)).append('"')
            append(",\"libPath\":\"").append(esc(libPath)).append('"')
            append(",\"remoteHost\":\"").append(esc(remoteHost)).append('"')
            append(",\"remotePort\":").append(remotePort)
            append(",\"remotePassword\":\"").append(esc(remotePassword)).append('"')
            append(",\"remotePlatform\":\"").append(esc(remotePlatform)).append('"')
            append('}')
        }
    }

    companion object {
        /**
         * 由 JSON 字符串构造配置；解析失败的字段使用默认值。
         */
        fun fromJson(json: String): EngineConfig {
            val map = parseSimpleJson(json)
            val typeStr = map["type"] ?: "katago"
            val type = when (typeStr.lowercase()) {
                "leelazero", "leela" -> EngineType.LEELAZERO
                "remote" -> EngineType.REMOTE
                else -> EngineType.KATAGO
            }
            return EngineConfig(
                type = type,
                weightsPath = map["weightsPath"] ?: "",
                threads = (map["threads"] ?: "2").toIntOrNull() ?: 2,
                maxVisits = (map["maxVisits"] ?: "800").toIntOrNull() ?: 800,
                komi = (map["komi"] ?: "7.5").toDoubleOrNull() ?: 7.5,
                boardSize = (map["boardSize"] ?: "19").toIntOrNull() ?: 19,
                cpuOnly = (map["cpuOnly"] ?: "false").toBooleanStrictOrNull() ?: false,
                enablePonder = (map["enablePonder"] ?: "false").toBooleanStrictOrNull() ?: false,
                executablePath = map["executablePath"] ?: "",
                configPath = map["configPath"] ?: "",
                workingDir = map["workingDir"] ?: "",
                libPath = map["libPath"] ?: "",
                remoteHost = map["remoteHost"] ?: "",
                remotePort = (map["remotePort"] ?: "0").toIntOrNull() ?: 0,
                remotePassword = map["remotePassword"] ?: "",
                remotePlatform = map["remotePlatform"] ?: ""
            )
        }

        // 极简 JSON 解析，仅支持本类使用的扁平字符串/数字/布尔结构。
        private fun parseSimpleJson(json: String): Map<String, String> {
            val result = mutableMapOf<String, String>()
            val src = json.trim()
            if (!src.startsWith("{") || !src.endsWith("}")) return result
            val body = src.substring(1, src.length - 1)
            var i = 0
            while (i < body.length) {
                // 跳过空白与逗号
                while (i < body.length && (body[i].isWhitespace() || body[i] == ',')) i++
                if (i >= body.length) break
                if (body[i] != '"') { i++; continue }
                // 读取 key
                val keyStart = i + 1
                var keyEnd = keyStart
                while (keyEnd < body.length && body[keyEnd] != '"') {
                    if (body[keyEnd] == '\\' && keyEnd + 1 < body.length) keyEnd += 2 else keyEnd++
                }
                val key = body.substring(keyStart, keyEnd).unescape()
                i = keyEnd + 1
                while (i < body.length && body[i].isWhitespace()) i++
                if (i >= body.length || body[i] != ':') break
                i++
                while (i < body.length && body[i].isWhitespace()) i++
                if (i >= body.length) break
                // 读取 value
                val value: String = when {
                    body[i] == '"' -> {
                        val vStart = i + 1
                        var vEnd = vStart
                        while (vEnd < body.length && body[vEnd] != '"') {
                            if (body[vEnd] == '\\' && vEnd + 1 < body.length) vEnd += 2 else vEnd++
                        }
                        val v = body.substring(vStart, vEnd).unescape()
                        i = vEnd + 1
                        v
                    }
                    else -> {
                        val vStart = i
                        var vEnd = vStart
                        while (vEnd < body.length && body[vEnd] != ',' && body[vEnd] != '}') vEnd++
                        val v = body.substring(vStart, vEnd).trim()
                        i = vEnd
                        v
                    }
                }
                result[key] = value
            }
            return result
        }

        private fun String.unescape(): String =
            replace("\\\"", "\"").replace("\\\\", "\\")
    }
}

/**
 * 将 [EngineConfig]（持久化结构）转换为远程模块使用的 [com.weiqi.app.remote.RemoteConfig]。
 *
 * EngineConfig 的 `remotePlatform` 字段约定取值：
 * - "zhixing" / "zhixingcloud" → 智星云
 * - "suanyun" → 算云
 * - "ssh" → SSH 隧道
 * - 其他（含 "custom" / 空）→ 个人 PC 直连
 */
fun EngineConfig.toRemoteConfig(): com.weiqi.app.remote.RemoteConfig {
    val platform = when (remotePlatform.lowercase().trim()) {
        "zhixing", "zhixingcloud" -> com.weiqi.app.remote.RemotePlatform.ZHIXING_CLOUD
        "suanyun" -> com.weiqi.app.remote.RemotePlatform.SUANYUN
        "ssh", "sshtunnel" -> com.weiqi.app.remote.RemotePlatform.SSH_TUNNEL
        else -> com.weiqi.app.remote.RemotePlatform.CUSTOM_PC
    }
    return com.weiqi.app.remote.RemoteConfig(
        host = remoteHost,
        port = if (remotePort > 0) remotePort else 8080,
        password = remotePassword,
        platform = platform,
        enginePath = remotePlatform,        // 平台标识符回填为引擎路径名（参考用）
        weightsPath = weightsPath.ifBlank { "b18c384" }
    )
}
