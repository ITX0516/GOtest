#ifndef WEIQI_LEELAZ_BRIDGE_H
#define WEIQI_LEELAZ_BRIDGE_H

#include <string>

// ============================================================================
// LeelaZero 引擎 C 接口约定（Android .so 需要导出的符号）
//
// 与 kata_bridge.h 保持对称，便于上层统一抽象。
// ============================================================================

#ifdef __cplusplus
extern "C" {
#endif

// ===== 生命周期 =====

void* leelaz_create(const char* config_json,
                    char* error_msg,
                    int error_msg_len);

void leelaz_destroy(void* handle);

// ===== 同步 GTP =====

char* leelaz_send_command(void* handle,
                          const char* command,
                          char* error_msg,
                          int error_msg_len);

void leelaz_free_string(char* str);

// ===== 流式分析 =====

typedef void (*LeelazAnalysisCallback)(void* user_data, const char* line);

bool leelaz_start_analysis(void* handle,
                           const char* command,
                           LeelazAnalysisCallback callback,
                           void* user_data);

void leelaz_stop_analysis(void* handle);

// ===== 查询 =====

bool leelaz_is_ready(void* handle);
const char* leelaz_name(void* handle);
const char* leelaz_version(void* handle);

#ifdef __cplusplus
} // extern "C"
#endif

#endif // WEIQI_LEELAZ_BRIDGE_H
