#ifndef WEIQI_APP_LOG_H
#define WEIQI_APP_LOG_H

#include <jni.h>
#include <string>

namespace weiqi {
namespace log {

/**
 * 初始化 native 日志桥接。
 * 必须在 JNI_OnLoad 中调用，传入 JavaVM 以便后续 attach 到 JNI 线程
 * 调用 Kotlin 端 AppLogger.nativeWriteLog()。
 *
 * 同时安装 SIGSEGV/SIGABRT/SIGBUS 信号处理，崩溃时把 backtrace
 * 写入日志文件，便于在没有 logcat 的环境下定位闪退原因。
 */
void init(JavaVM* vm);

/** 写一条日志到文件（同时输出到 logcat）。 */
void write(const char* level, const char* tag, const std::string& msg);

// 便捷接口
inline void v(const char* tag, const std::string& msg) { write("V", tag, msg); }
inline void d(const char* tag, const std::string& msg) { write("D", tag, msg); }
inline void i(const char* tag, const std::string& msg) { write("I", tag, msg); }
inline void w(const char* tag, const std::string& msg) { write("W", tag, msg); }
inline void e(const char* tag, const std::string& msg) { write("E", tag, msg); }

} // namespace log
} // namespace weiqi

#endif // WEIQI_APP_LOG_H
