#include "app_log.h"

#include <android/log.h>
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>

#include <atomic>
#include <unwind.h>
#include <mutex>

#define ANDROID_LOG_TAG "weiqi_native"

namespace weiqi {
namespace log {

namespace {

JavaVM* g_vm = nullptr;
jclass g_loggerClass = nullptr;
jmethodID g_writeMethod = nullptr;
std::mutex g_jniMutex;

// crash handler 状态
std::atomic<bool> g_inCrash{false};
struct sigaction g_oldSigsegv{};
struct sigaction g_oldSigabrt{};
struct sigaction g_oldSigbus{};

/** Attach 当前线程到 JVM，返回 JNIEnv（自动 detach）。 */
class ScopedJni {
public:
    ScopedJni() : env_(nullptr), attached_(false) {
        if (g_vm == nullptr) return;
        if (g_vm->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) {
            return;
        }
        if (g_vm->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }
    ~ScopedJni() {
        if (attached_ && g_vm != nullptr) {
            g_vm->DetachCurrentThread();
        }
    }
    JNIEnv* env() const { return env_; }
    bool ok() const { return env_ != nullptr; }
private:
    JNIEnv* env_;
    bool attached_;
};

/** 调用 Kotlin AppLogger.nativeWriteLog(level, tag, msg)。 */
void callKotlinLog(const char* level, const char* tag, const std::string& msg) {
    if (g_vm == nullptr || g_loggerClass == nullptr || g_writeMethod == nullptr) return;
    ScopedJni jni;
    if (!jni.ok()) return;
    JNIEnv* env = jni.env();
    jstring jlevel = env->NewStringUTF(level);
    jstring jtag = env->NewStringUTF(tag);
    jstring jmsg = env->NewStringUTF(msg.c_str());
    if (jlevel && jtag && jmsg) {
        env->CallStaticVoidMethod(g_loggerClass, g_writeMethod, jlevel, jtag, jmsg);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    if (jlevel) env->DeleteLocalRef(jlevel);
    if (jtag) env->DeleteLocalRef(jtag);
    if (jmsg) env->DeleteLocalRef(jmsg);
}

// ===== native backtrace 收集（用于 crash handler）=====
struct BacktraceState {
    void** current;
    void** end;
};

_Unwind_Reason_Code unwindCallback(struct _Unwind_Context* ctx, void* arg) {
    auto* state = static_cast<BacktraceState*>(arg);
    if (state->current == state->end) return _URC_END_OF_STACK;
    uintptr_t pc = _Unwind_GetIP(ctx);
    if (pc != 0) {
        *state->current++ = reinterpret_cast<void*>(pc);
    }
    return _URC_NO_REASON;
}

size_t captureBacktrace(void** buffer, size_t max) {
    BacktraceState state{buffer, buffer + max};
    _Unwind_Backtrace(unwindCallback, &state);
    return state.current - buffer;
}

/** 把地址解析成 "模块+offset (symbol)" 字符串。 */
std::string addrToSymbol(void* addr) {
    Dl_info info;
    if (dladdr(addr, &info) == 0) {
        char buf[64];
        snprintf(buf, sizeof(buf), "%p (unknown)", addr);
        return buf;
    }
    const char* fname = info.dli_fname ? info.dli_fname : "?";
    const char* sname = info.dli_sname ? info.dli_sname : "?";
    uintptr_t rel = reinterpret_cast<uintptr_t>(addr) -
                    reinterpret_cast<uintptr_t>(info.dli_fbase);
    char buf[256];
    snprintf(buf, sizeof(buf), "%p  %s+0x%lx in %s",
             addr, sname, (unsigned long)rel, fname);
    return buf;
}

/** 写 native backtrace 到日志（同步 flush，避免在 crash 时丢日志）。 */
void writeCrashDump(int sig, siginfo_t* info, void* /*uctx*/) {
    // 防止递归崩溃
    if (g_inCrash.exchange(true)) {
        // 已经在处理崩溃中，直接走默认 handler
        if (sig == SIGSEGV && g_oldSigsegv.sa_sigaction) {
            g_oldSigsegv.sa_sigaction(sig, info, nullptr);
        } else if (sig == SIGABRT && g_oldSigabrt.sa_sigaction) {
            g_oldSigabrt.sa_sigaction(sig, info, nullptr);
        }
        return;
    }

    // 1. 写崩溃头
    char header[256];
    snprintf(header, sizeof(header),
             "\n"
             "================================================\n"
             "!!! NATIVE CRASH !!!\n"
             "信号: %d (%s)\n"
             "地址: %p (si_code=%d)\n"
             "线程: pid=%d tid=%d\n"
             "================================================",
             sig, strsignal(sig),
             info ? info->si_addr : nullptr,
             info ? info->si_code : 0,
             getpid(), static_cast<int>(pthread_self()));
    callKotlinLog("F", "CRASH", header);

    // 2. 收集 backtrace
    void* frames[32];
    size_t n = captureBacktrace(frames, 32);
    std::string bt = "Backtrace:";
    for (size_t i = 0; i < n; ++i) {
        bt += "\n  #" + std::to_string(i) + " " + addrToSymbol(frames[i]);
    }
    if (n == 0) {
        bt += "\n  (no frames captured; may need unwind tables)";
    }
    callKotlinLog("F", "CRASH", bt);

    // 3. 调用原 handler 让系统正常崩溃（产生 tombstone）
    struct sigaction* old_act = nullptr;
    if (sig == SIGSEGV) old_act = &g_oldSigsegv;
    else if (sig == SIGABRT) old_act = &g_oldSigabrt;
    else if (sig == SIGBUS) old_act = &g_oldSigbus;

    if (old_act != nullptr) {
        if (old_act->sa_flags & SA_SIGINFO) {
            if (old_act->sa_sigaction) old_act->sa_sigaction(sig, info, nullptr);
        } else {
            if (old_act->sa_handler == SIG_DFL) {
                // 恢复默认 handler 并重新发送信号
                signal(sig, SIG_DFL);
                raise(sig);
            } else if (old_act->sa_handler != SIG_IGN && old_act->sa_handler != nullptr) {
                old_act->sa_handler(sig);
            }
        }
    }
    // 兜底
    signal(sig, SIG_DFL);
    raise(sig);
}

void installCrashHandler() {
    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = writeCrashDump;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);

    sigaction(SIGSEGV, &sa, &g_oldSigsegv);
    sigaction(SIGABRT, &sa, &g_oldSigabrt);
    sigaction(SIGBUS,  &sa, &g_oldSigbus);
}

} // anonymous namespace

void init(JavaVM* vm) {
    g_vm = vm;
    if (vm == nullptr) return;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, ANDROID_LOG_TAG,
                           "log::init: GetEnv failed");
        return;
    }

    const char* kClassName = "com/weiqi/app/util/AppLogger";
    jclass local = env->FindClass(kClassName);
    if (local == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, ANDROID_LOG_TAG,
                           "log::init: cannot find class %s", kClassName);
        return;
    }
    g_loggerClass = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);

    g_writeMethod = env->GetStaticMethodID(
        g_loggerClass, "nativeWriteLog",
        "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    if (g_writeMethod == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, ANDROID_LOG_TAG,
                           "log::init: cannot find nativeWriteLog method");
        return;
    }

    installCrashHandler();
    __android_log_print(ANDROID_LOG_INFO, ANDROID_LOG_TAG,
                       "log::init: ok, crash handlers installed");
}

void write(const char* level, const char* tag, const std::string& msg) {
    // 同时输出到 logcat
    int prio = ANDROID_LOG_UNKNOWN;
    if (strcmp(level, "V") == 0) prio = ANDROID_LOG_VERBOSE;
    else if (strcmp(level, "D") == 0) prio = ANDROID_LOG_DEBUG;
    else if (strcmp(level, "I") == 0) prio = ANDROID_LOG_INFO;
    else if (strcmp(level, "W") == 0) prio = ANDROID_LOG_WARN;
    else if (strcmp(level, "E") == 0) prio = ANDROID_LOG_ERROR;
    else if (strcmp(level, "F") == 0) prio = ANDROID_LOG_FATAL;
    __android_log_print(prio, tag, "%s", msg.c_str());

    // 写文件（通过 JNI 回调 Kotlin AppLogger）
    callKotlinLog(level, tag, msg);
}

} // namespace log
} // namespace weiqi
