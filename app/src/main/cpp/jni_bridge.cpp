// jni_bridge.cpp
// Kotlin NativeEngineBridge ↔ C++ GtpEngine 桥接实现。
//
// 支持四种集成模式（由 CMakeLists.txt 的 WEIQI_ENGINE_MODE 控制）：
//   - STUB    : StubGtpEngine，桩实现，UI 调试用
//   - PROCESS : KataGoRealEngine / LeelazRealEngine，子进程 + GTP pipe
//   - DYLIB   : DylibGtpEngine，dlopen 引擎 .so
//   - BUILTIN : BuiltinKataGoEngine，KataGo 源码直接编译进 .so
//
// 实现的所有 native 方法（与 NativeEngineBridge.kt 一一对应）：
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeCreateEngine
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeDestroyEngine
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeSendGtpCommand
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeStartAnalysis
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeStopAnalysis
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeIsEngineAvailable
//   Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeGetEngineVersion

#include <jni.h>

#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gtp_engine.h"

#if defined(WEIQI_BUILTIN_MODE)
    #include "builtin_katago_engine.h"
#elif defined(WEIQI_PROCESS_MODE)
    #include "real_engines.h"
    #include <stdlib.h>
#elif defined(WEIQI_DYLIB_MODE)
    #include "dylib_engine.h"
    #include <dlfcn.h>
#endif

#define LOG_TAG "weiqi_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

// ============================================================================
// 全局缓存：JavaVM、NativeEngineBridge 类引用与回调方法 ID
// ============================================================================

static JavaVM* g_jvm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_onAnalysisUpdateMethod = nullptr;

// 返回当前线程的 JNIEnv；若线程未 attached 则临时 attach，用毕 detach。
class ScopedJniEnv {
public:
    explicit ScopedJniEnv(JavaVM* vm) : vm_(vm), env_(nullptr), attached_(false) {
        if (vm_ == nullptr) return;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6) == JNI_OK) {
            return;
        }
        if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
            attached_ = true;
        }
    }
    ~ScopedJniEnv() {
        if (attached_ && vm_ != nullptr) {
            vm_->DetachCurrentThread();
        }
    }
    JNIEnv* env() const { return env_; }
    bool ok() const { return env_ != nullptr; }

private:
    JavaVM* vm_;
    JNIEnv* env_;
    bool attached_;
};

// 反向调用 NativeEngineBridge.onAnalysisUpdate(callbackId, line)
static void callOnAnalysisUpdate(int callbackId, const std::string& line) {
    if (g_jvm == nullptr || g_bridgeClass == nullptr || g_onAnalysisUpdateMethod == nullptr) {
        return;
    }
    ScopedJniEnv env(g_jvm);
    if (!env.ok()) return;
    jstring jline = env.env()->NewStringUTF(line.c_str());
    if (jline == nullptr) return;
    env.env()->CallStaticVoidMethod(g_bridgeClass, g_onAnalysisUpdateMethod,
                                    static_cast<jint>(callbackId), jline);
    env.env()->DeleteLocalRef(jline);
    if (env.env()->ExceptionCheck()) {
        env.env()->ExceptionClear();
    }
}

// ============================================================================
// 简易 JSON 解析（仅提取字符串字段）—— 用于从 configJson 中取关键字段
// ============================================================================

static std::string jsonGetString(const std::string& json, const std::string& key) {
    std::string search = "\"" + key + "\":";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return "";
    pos += search.size();
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) pos++;
    if (pos >= json.size()) return "";
    if (json[pos] == '"') {
        pos++;
        size_t end = pos;
        while (end < json.size() && json[end] != '"') {
            if (json[end] == '\\' && end + 1 < json.size()) end += 2;
            else end++;
        }
        std::string result = json.substr(pos, end - pos);
        // 简易去转义
        std::string unescaped;
        for (size_t i = 0; i < result.size(); ++i) {
            if (result[i] == '\\' && i + 1 < result.size()) {
                unescaped.push_back(result[i + 1]);
                i++;
            } else {
                unescaped.push_back(result[i]);
            }
        }
        return unescaped;
    }
    return "";
}

static int jsonGetInt(const std::string& json, const std::string& key, int defaultVal) {
    std::string search = "\"" + key + "\":";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return defaultVal;
    pos += search.size();
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) pos++;
    std::string num;
    while (pos < json.size() && (isdigit(json[pos]) || json[pos] == '-')) {
        num.push_back(json[pos]);
        pos++;
    }
    if (num.empty()) return defaultVal;
    try { return std::stoi(num); } catch (...) { return defaultVal; }
}

static double jsonGetDouble(const std::string& json, const std::string& key, double defaultVal) {
    std::string search = "\"" + key + "\":";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return defaultVal;
    pos += search.size();
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) pos++;
    std::string num;
    while (pos < json.size() && (isdigit(json[pos]) || json[pos] == '-' || json[pos] == '.' || json[pos] == 'e' || json[pos] == 'E')) {
        num.push_back(json[pos]);
        pos++;
    }
    if (num.empty()) return defaultVal;
    try { return std::stod(num); } catch (...) { return defaultVal; }
}

static bool jsonGetBool(const std::string& json, const std::string& key, bool defaultVal) {
    std::string search = "\"" + key + "\":";
    size_t pos = json.find(search);
    if (pos == std::string::npos) return defaultVal;
    pos += search.size();
    while (pos < json.size() && (json[pos] == ' ' || json[pos] == '\t')) pos++;
    if (json.substr(pos, 4) == "true") return true;
    if (json.substr(pos, 5) == "false") return false;
    return defaultVal;
}

// ============================================================================
// 引擎工厂：根据编译模式返回真实引擎或桩实现
// ============================================================================

static std::unique_ptr<weiqi::GtpEngine> createEngineInstance(int engineType,
                                                              const std::string& configJson) {
    // === BUILTIN 模式：KataGo 源码内置编译 ===
#if defined(WEIQI_BUILTIN_MODE)
    if (engineType == weiqi::kEngineKatago) {
        weiqi::BuiltinKataGoEngine::Config cfg;
        cfg.weightsPath = jsonGetString(configJson, "weightsPath");
        cfg.configPath = jsonGetString(configJson, "configPath");
        cfg.threads = jsonGetInt(configJson, "threads", 2);
        cfg.maxVisits = jsonGetInt(configJson, "maxVisits", 800);
        cfg.komi = jsonGetDouble(configJson, "komi", 7.5);
        cfg.boardSize = jsonGetInt(configJson, "boardSize", 19);
        cfg.cpuOnly = jsonGetBool(configJson, "cpuOnly", true);
        cfg.enablePonder = jsonGetBool(configJson, "enablePonder", false);
        cfg.nnCacheSizePowerOfTwo = jsonGetInt(configJson, "nnCacheSizePowerOfTwo", 17);
        return std::make_unique<weiqi::BuiltinKataGoEngine>(cfg);
    }
    // LeelaZero 不支持 BUILTIN 模式
    if (engineType == weiqi::kEngineLeelazero) {
        return nullptr;
    }
    return nullptr;

    // === PROCESS 模式：子进程 + GTP pipe ===
#elif defined(WEIQI_PROCESS_MODE)
    std::string weightsPath = jsonGetString(configJson, "weightsPath");
    std::string configPath = jsonGetString(configJson, "configPath");
    int threads = jsonGetInt(configJson, "threads", 2);
    double komi = jsonGetDouble(configJson, "komi", 7.5);
    int boardSize = jsonGetInt(configJson, "boardSize", 19);
    bool cpuOnly = jsonGetBool(configJson, "cpuOnly", true);
    std::string workingDir = jsonGetString(configJson, "workingDir");

    // 可执行文件路径：
    //   - 优先使用 json 中的 executablePath
    //   - 否则按 workingDir + "katago"/"leelaz" 推断
    std::string execPath = jsonGetString(configJson, "executablePath");
    if (execPath.empty()) {
        std::string name = (engineType == weiqi::kEngineKatago) ? "katago" : "leelaz";
        execPath = workingDir.empty() ? name : workingDir + "/" + name;
    }

    if (engineType == weiqi::kEngineKatago) {
        auto engine = std::make_unique<weiqi::KataGoRealEngine>(
            weightsPath, configPath, threads, cpuOnly, komi, boardSize, workingDir, execPath);
        return engine;
    } else if (engineType == weiqi::kEngineLeelazero) {
        auto engine = std::make_unique<weiqi::LeelazRealEngine>(
            weightsPath, threads, cpuOnly, komi, boardSize, workingDir, execPath);
        return engine;
    }
    return nullptr;

    // === DYLIB 模式：dlopen 动态库 ===
#elif defined(WEIQI_DYLIB_MODE)
    std::string libPath;
    if (engineType == weiqi::kEngineKatago) {
        libPath = jsonGetString(configJson, "libPath");
        if (libPath.empty()) libPath = "libkatago.so";
    } else if (engineType == weiqi::kEngineLeelazero) {
        libPath = jsonGetString(configJson, "libPath");
        if (libPath.empty()) libPath = "libleelaz.so";
    } else {
        return nullptr;
    }
    return std::make_unique<weiqi::DylibGtpEngine>(engineType, libPath, configJson);

    // === STUB 模式（默认）：桩实现 ===
#else
    (void)configJson;
    if (engineType != weiqi::kEngineKatago && engineType != weiqi::kEngineLeelazero) {
        return nullptr;
    }
    return std::make_unique<weiqi::StubGtpEngine>(engineType);
#endif
}

// handle ↔ GtpEngine* 转换辅助
static inline weiqi::GtpEngine* handleToEngine(jlong handle) {
    return reinterpret_cast<weiqi::GtpEngine*>(handle);
}

// ============================================================================
// JNI_OnLoad：缓存 JavaVM、类引用、方法 ID
// ============================================================================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    const char* kClassName = "com/weiqi/app/engine/jni/NativeEngineBridge";
    jclass localClass = env->FindClass(kClassName);
    if (localClass == nullptr) {
        LOGE("JNI_OnLoad: cannot find class %s", kClassName);
        return JNI_ERR;
    }
    g_bridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (g_bridgeClass == nullptr) return JNI_ERR;

    g_onAnalysisUpdateMethod = env->GetStaticMethodID(
        g_bridgeClass, "onAnalysisUpdate", "(ILjava/lang/String;)V");
    if (g_onAnalysisUpdateMethod == nullptr) {
        LOGE("JNI_OnLoad: cannot find onAnalysisUpdate method");
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: weiqi_engine bridge ready");
    return JNI_VERSION_1_6;
}

// ============================================================================
// Native 方法实现
// ============================================================================

extern "C" {

// 创建引擎实例，返回 handle（GtpEngine* 转 jlong）；失败返回 0
JNIEXPORT jlong JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeCreateEngine(
    JNIEnv* env, jclass /*clazz*/, jint engineType, jstring configJson) {

    std::string config;
    if (configJson != nullptr) {
        const char* chars = env->GetStringUTFChars(configJson, nullptr);
        if (chars != nullptr) {
            config = chars;
            env->ReleaseStringUTFChars(configJson, chars);
        }
    }

    auto engine = createEngineInstance(static_cast<int>(engineType), config);
    if (!engine) {
        LOGE("createEngine: failed to create engine instance (type=%d)", (int)engineType);
        return 0;
    }
    if (!engine->start()) {
        LOGE("createEngine: engine.start() failed (type=%d)", (int)engineType);
        return 0;
    }
    LOGI("createEngine: engine started (type=%d, name=%s)", (int)engineType, engine->name().c_str());
    return reinterpret_cast<jlong>(engine.release());
}

// 销毁引擎实例
JNIEXPORT void JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeDestroyEngine(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    weiqi::GtpEngine* engine = handleToEngine(handle);
    if (engine != nullptr) {
        engine->shutdown();
        delete engine;
    }
}

// 发送 GTP 命令，返回响应正文；失败返回 "error: <msg>"
JNIEXPORT jstring JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeSendGtpCommand(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring command) {
    if (handle == 0) {
        return env->NewStringUTF("error: engine not created");
    }
    weiqi::GtpEngine* engine = handleToEngine(handle);
    if (engine == nullptr) {
        return env->NewStringUTF("error: invalid engine handle");
    }

    std::string cmd;
    if (command != nullptr) {
        const char* chars = env->GetStringUTFChars(command, nullptr);
        if (chars != nullptr) {
            cmd = chars;
            env->ReleaseStringUTFChars(command, chars);
        }
    }

    std::string errorMessage;
    std::string response = engine->sendCommand(cmd, errorMessage);
    if (!errorMessage.empty()) {
        std::string err = "error: " + errorMessage;
        return env->NewStringUTF(err.c_str());
    }
    return env->NewStringUTF(response.c_str());
}

// 启动流式分析
JNIEXPORT void JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeStartAnalysis(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring command, jint callbackId) {
    if (handle == 0) return;
    weiqi::GtpEngine* engine = handleToEngine(handle);
    if (engine == nullptr) return;

    std::string cmd;
    if (command != nullptr) {
        const char* chars = env->GetStringUTFChars(command, nullptr);
        if (chars != nullptr) {
            cmd = chars;
            env->ReleaseStringUTFChars(command, chars);
        }
    }

    int cbId = static_cast<int>(callbackId);
    engine->startAnalysis(cmd, [cbId](const std::string& line) {
        callOnAnalysisUpdate(cbId, line);
    });
}

// 停止流式分析
JNIEXPORT void JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeStopAnalysis(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    weiqi::GtpEngine* engine = handleToEngine(handle);
    if (engine != nullptr) {
        engine->stopAnalysis();
    }
}

// 检查引擎类型是否可用
JNIEXPORT jboolean JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeIsEngineAvailable(
    JNIEnv* /*env*/, jclass /*clazz*/, jint engineType) {

    if (engineType != weiqi::kEngineKatago && engineType != weiqi::kEngineLeelazero) {
        return JNI_FALSE;
    }

#if defined(WEIQI_BUILTIN_MODE)
    // BUILTIN 模式：KataGo 始终可用（编译进 .so 了），LeelaZero 不可用
    if (engineType == weiqi::kEngineKatago) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
#elif defined(WEIQI_PROCESS_MODE)
    // PROCESS 模式：理论上总是可用（只要有可执行文件）
    // 这里返回 true，实际可用性由 start() 决定
    return JNI_TRUE;
#elif defined(WEIQI_DYLIB_MODE)
    // DYLIB 模式：尝试 dlopen 看能否加载
    const char* libName = (engineType == weiqi::kEngineKatago) ? "libkatago.so" : "libleelaz.so";
    void* handle = dlopen(libName, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        dlclose(handle);
        return JNI_TRUE;
    }
    return JNI_FALSE;
#else
    // STUB 模式：始终可用
    return JNI_TRUE;
#endif
}

// 获取引擎版本字符串
JNIEXPORT jstring JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeGetEngineVersion(
    JNIEnv* env, jclass /*clazz*/, jint engineType) {
    auto engine = createEngineInstance(static_cast<int>(engineType), "");
    std::string ver = engine ? engine->version() : "unknown";
    // 如果只是查询版本而不实际启动，STUB 模式没问题；
    // PROCESS/DYLIB 模式不应该调这个方法来探测（应该用 isEngineAvailable）
    return env->NewStringUTF(ver.c_str());
}

} // extern "C"
