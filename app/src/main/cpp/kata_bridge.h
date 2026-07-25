#ifndef WEIQI_KATA_BRIDGE_H
#define WEIQI_KATA_BRIDGE_H

#include <string>
#include <vector>

// ============================================================================
// KataGo 引擎 C 接口约定（Android .so 需要导出的符号）
//
// 如果你已有 libkatago.so 但没有下列 C 函数，可参考本文件约定
// 在 KataGo 源码中加一层薄包装后重新编译。
//
// 设计原则：
//   - 纯 C ABI，无 C++ 类/STL，避免跨编译器 ABI 不兼容
//   - 所有字符串以 UTF-8 '\0' 结尾
//   - 句柄用不透明指针 void*
//   - 调用方负责释放返回的字符串（调用 katago_free_string）
// ============================================================================

#ifdef __cplusplus
extern "C" {
#endif

// ===== 生命周期 =====

// 创建一个 KataGo 引擎实例。
// 参数：
//   config_json: JSON 字符串，至少包含：
//     {
//       "weightsPath": "/data/.../katago_b18c384.bin.gz",
//       "configPath":  "/data/.../default_gtp.cfg",   // 可选，无则用内置默认
//       "threads": 2,
//       "maxVisits": 800,
//       "komi": 7.5,
//       "boardSize": 19,
//       "cpuOnly": true,
//       "enablePonder": false,
//       "nnCacheSizePowerOfTwo": 17,   // 可选，默认 17
//       "numSearchThreads": 2          // 可选，与 threads 同义
//     }
//   error_msg: 输出错误信息缓冲区，失败时写入。可传 nullptr。
//   error_msg_len: 缓冲区大小。
// 返回：成功返回句柄（非空），失败返回 nullptr。
void* katago_create(const char* config_json,
                    char* error_msg,
                    int error_msg_len);

// 销毁引擎实例，释放所有资源。
void katago_destroy(void* handle);

// ===== 同步 GTP =====

// 发送一条 GTP 命令并同步等待响应。
// 参数：
//   command: GTP 命令（不含末尾换行）。
//   error_msg: 失败时写入错误信息，可为 nullptr。
//   error_msg_len: 缓冲区大小。
// 返回：
//   响应正文（已去掉 "=" 前缀与结尾空行）。
//   失败返回 nullptr。返回的字符串必须用 katago_free_string 释放。
char* katago_send_command(void* handle,
                          const char* command,
                          char* error_msg,
                          int error_msg_len);

// 释放由 katago_send_command 返回的字符串。
void katago_free_string(char* str);

// ===== 流式分析 =====

// 流式分析回调：每收到一行完整的分析输出即调用一次。
// 参数：
//   user_data: 用户透传指针
//   line: 一行分析输出（不含换行符）
typedef void (*KatagoAnalysisCallback)(void* user_data, const char* line);

// 启动流式分析（kata-analyze）。
// 参数：
//   command: 分析命令，如 "kata-analyze b interval 100 visits 800 json 1"
//   callback: 每行回调
//   user_data: 透传给回调的指针
// 返回：成功 true，失败 false
bool katago_start_analysis(void* handle,
                           const char* command,
                           KatagoAnalysisCallback callback,
                           void* user_data);

// 停止流式分析。
void katago_stop_analysis(void* handle);

// ===== 查询 =====

// 引擎是否就绪（初始化完成且未崩溃）。
bool katago_is_ready(void* handle);

// 引擎名称（"KataGo"）。
const char* katago_name(void* handle);

// 引擎版本号（如 "1.13.2"）。
const char* katago_version(void* handle);

// 支持的最大棋盘尺寸。
int katago_max_board_size(void* handle);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // WEIQI_KATA_BRIDGE_H
