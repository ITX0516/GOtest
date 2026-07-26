// jni_bridge.cpp
// Kotlin NativeEngineBridge ↔ C++ GtpEngine 桥接实现。
//
// 模式（由 CMakeLists.txt 的 WEIQI_ENGINE_MODE 控制）：
//   - PROCESS : KataGoRealEngine / LeelazRealEngine，子进程 + GTP pipe
//   - DYLIB   : DylibGtpEngine，dlopen 引擎 .so

#include <jni.h>

#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "gtp_engine.h"
#include "app_log.h"

#if defined(WEIQI_PROCESS_MODE)
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
// 简易 JSON 解析
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
// 引擎工厂
// ============================================================================

static std::unique_ptr<weiqi::GtpEngine> createEngineInstance(int engineType,
                                                              const std::string& configJson) {
#if defined(WEIQI_PROCESS_MODE)
    std::string weightsPath = jsonGetString(configJson, "weightsPath");
    std::string configPath = jsonGetString(configJson, "configPath");
    int threads = jsonGetInt(configJson, "threads", 2);
    double komi = jsonGetDouble(configJson, "komi", 7.5);
    int boardSize = jsonGetInt(configJson, "boardSize", 19);
    bool cpuOnly = jsonGetBool(configJson, "cpuOnly", true);
    std::string workingDir = jsonGetString(configJson, "workingDir");

    std::string execPath = jsonGetString(configJson, "executablePath");
    if (execPath.empty()) {
        std::string name = (engineType == weiqi::kEngineKatago) ? "katago" : "leelaz";
        execPath = workingDir.empty() ? name : workingDir + "/" + name;
    }

    if (engineType == weiqi::kEngineKatago) {
        return std::make_unique<weiqi::KataGoRealEngine>(
            weightsPath, configPath, threads, cpuOnly, komi, boardSize, workingDir, execPath);
    } else if (engineType == weiqi::kEngineLeelazero) {
        return std::make_unique<weiqi::LeelazRealEngine>(
            weightsPath, threads, cpuOnly, komi, boardSize, workingDir, execPath);
    }

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
#endif

    return nullptr;
}

static inline weiqi::GtpEngine* handleToEngine(jlong handle) {
    return reinterpret_cast<weiqi::GtpEngine*>(handle);
}

// ============================================================================
// JNI_OnLoad
// ============================================================================

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;

    // 必须最先初始化日志系统（含 native crash 信号处理）
    weiqi::log::init(vm);
    weiqi::log::i("weiqi_jni", "JNI_OnLoad 开始");

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        weiqi::log::e("weiqi_jni", "JNI_OnLoad: GetEnv failed");
        return JNI_ERR;
    }

    const char* kClassName = "com/weiqi/app/engine/jni/NativeEngineBridge";
    jclass localClass = env->FindClass(kClassName);
    if (localClass == nullptr) {
        weiqi::log::e("weiqi_jni", std::string("JNI_OnLoad: cannot find class ") + kClassName);
        return JNI_ERR;
    }
    g_bridgeClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (g_bridgeClass == nullptr) {
        weiqi::log::e("weiqi_jni", "JNI_OnLoad: NewGlobalRef failed");
        return JNI_ERR;
    }

    g_onAnalysisUpdateMethod = env->GetStaticMethodID(
        g_bridgeClass, "onAnalysisUpdate", "(ILjava/lang/String;)V");
    if (g_onAnalysisUpdateMethod == nullptr) {
        weiqi::log::e("weiqi_jni", "JNI_OnLoad: cannot find onAnalysisUpdate method");
        return JNI_ERR;
    }

    weiqi::log::i("weiqi_jni", "JNI_OnLoad: weiqi_engine bridge ready");
    return JNI_VERSION_1_6;
}

// ============================================================================
// Native 方法实现
// ============================================================================

extern "C" {

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

    std::string typeStr = (engineType == 1) ? "KATAGO" :
                          (engineType == 2) ? "LEELAZERO" :
                          std::string("UNKNOWN(") + std::to_string(engineType) + ")";
    weiqi::log::i("weiqi_jni", "nativeCreateEngine 开始 type=" + typeStr);
    weiqi::log::d("weiqi_jni", "config: " + config);

    auto engine = createEngineInstance(static_cast<int>(engineType), config);
    if (!engine) {
        weiqi::log::e("weiqi_jni", "createEngineInstance 返回 nullptr (type=" + typeStr +
                      ")，可能：引擎模式未匹配 / weightsPath 为空 / 未知引擎类型");
        return 0;
    }
    weiqi::log::i("weiqi_jni", "createEngineInstance 成功，开始 start()");

    if (!engine->start()) {
        weiqi::log::e("weiqi_jni", "engine->start() 返回 false (type=" + typeStr +
                      ")，子进程启动失败或立即退出");
        return 0;
    }
    weiqi::log::i("weiqi_jni", "engine->start() 成功: name=" + engine->name() +
                  " version=" + engine->version());
    return reinterpret_cast<jlong>(engine.release());
}

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
        return env->NewStringUTF(("error: " + errorMessage).c_str());
    }
    return env->NewStringUTF(response.c_str());
}

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

JNIEXPORT void JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeStopAnalysis(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    if (handle == 0) return;
    weiqi::GtpEngine* engine = handleToEngine(handle);
    if (engine != nullptr) {
        engine->stopAnalysis();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeIsEngineAvailable(
    JNIEnv* /*env*/, jclass /*clazz*/, jint engineType) {

    if (engineType != weiqi::kEngineKatago && engineType != weiqi::kEngineLeelazero) {
        return JNI_FALSE;
    }

#if defined(WEIQI_PROCESS_MODE)
    return JNI_TRUE;
#elif defined(WEIQI_DYLIB_MODE)
    const char* libName = (engineType == weiqi::kEngineKatago) ? "libkatago.so" : "libleelaz.so";
    void* handle = dlopen(libName, RTLD_NOW | RTLD_LOCAL);
    if (handle) {
        dlclose(handle);
        return JNI_TRUE;
    }
    return JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_weiqi_app_engine_jni_NativeEngineBridge_nativeGetEngineVersion(
    JNIEnv* env, jclass /*clazz*/, jint engineType) {
    auto engine = createEngineInstance(static_cast<int>(engineType), "");
    std::string ver = engine ? engine->version() : "unknown";
    return env->NewStringUTF(ver.c_str());
}

} // extern "C"
