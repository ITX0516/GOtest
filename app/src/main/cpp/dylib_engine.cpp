#include "dylib_engine.h"

#include <android/log.h>
#include <string.h>

#define LOG_TAG "weiqi_dylib"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace weiqi {

DylibGtpEngine::DylibGtpEngine(int engineType,
                               const std::string& libPath,
                               const std::string& configJson)
    : engineType_(engineType),
      libPath_(libPath),
      configJson_(configJson) {
    memset(&fn_, 0, sizeof(fn_));
}

DylibGtpEngine::~DylibGtpEngine() {
    shutdown();
}

bool DylibGtpEngine::start() {
    if (libPath_.empty()) {
        return false;
    }

    // dlopen
    libHandle_ = dlopen(libPath_.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!libHandle_) {
        LOGE("dlopen(%s) failed: %s", libPath_.c_str(), dlerror());
        return false;
    }

    if (!loadSymbols()) {
        dlclose(libHandle_);
        libHandle_ = nullptr;
        return false;
    }

    // 创建引擎实例
    char errBuf[512] = {0};
    if (engineType_ == kEngineKatago) {
        engineHandle_ = fn_.katago.create(configJson_.c_str(), errBuf, sizeof(errBuf));
    } else {
        engineHandle_ = fn_.leelaz.create(configJson_.c_str(), errBuf, sizeof(errBuf));
    }

    if (!engineHandle_) {
        LOGE("engine create failed: %s", errBuf);
        dlclose(libHandle_);
        libHandle_ = nullptr;
        return false;
    }

    return true;
}

void DylibGtpEngine::shutdown() {
    if (engineHandle_) {
        if (engineType_ == kEngineKatago) {
            fn_.katago.destroy(engineHandle_);
        } else {
            fn_.leelaz.destroy(engineHandle_);
        }
        engineHandle_ = nullptr;
    }
    if (libHandle_) {
        dlclose(libHandle_);
        libHandle_ = nullptr;
    }
    memset(&fn_, 0, sizeof(fn_));
}

bool DylibGtpEngine::isReady() const {
    if (!engineHandle_) return false;
    if (engineType_ == kEngineKatago) {
        return fn_.katago.is_ready(engineHandle_);
    } else {
        return fn_.leelaz.is_ready(engineHandle_);
    }
}

std::string DylibGtpEngine::name() const {
    if (!engineHandle_) return "DylibGtpEngine";
    const char* n = (engineType_ == kEngineKatago)
        ? fn_.katago.name(engineHandle_)
        : fn_.leelaz.name(engineHandle_);
    return n ? n : "unknown";
}

std::string DylibGtpEngine::version() const {
    if (!engineHandle_) return "0.0.0";
    const char* v = (engineType_ == kEngineKatago)
        ? fn_.katago.version(engineHandle_)
        : fn_.leelaz.version(engineHandle_);
    return v ? v : "unknown";
}

std::string DylibGtpEngine::sendCommand(const std::string& command,
                                         std::string& errorMessage) {
    errorMessage.clear();
    if (!engineHandle_) {
        errorMessage = "engine not started";
        return "";
    }

    char errBuf[256] = {0};
    char* resp = nullptr;

    if (engineType_ == kEngineKatago) {
        resp = fn_.katago.send_command(engineHandle_, command.c_str(), errBuf, sizeof(errBuf));
    } else {
        resp = fn_.leelaz.send_command(engineHandle_, command.c_str(), errBuf, sizeof(errBuf));
    }

    if (!resp) {
        errorMessage = errBuf;
        return "";
    }

    std::string result = resp;
    if (engineType_ == kEngineKatago) {
        fn_.katago.free_string(resp);
    } else {
        fn_.leelaz.free_string(resp);
    }
    return result;
}

void DylibGtpEngine::startAnalysis(const std::string& command,
                                   AnalysisCallback callback) {
    if (!engineHandle_) return;
    currentAnalysisCallback_ = std::move(callback);

    if (engineType_ == kEngineKatago) {
        fn_.katago.start_analysis(
            engineHandle_, command.c_str(),
            &DylibGtpEngine::analysisCallbackAdapter, this);
    } else {
        fn_.leelaz.start_analysis(
            engineHandle_, command.c_str(),
            &DylibGtpEngine::analysisCallbackAdapter, this);
    }
}

void DylibGtpEngine::stopAnalysis() {
    if (!engineHandle_) return;
    if (engineType_ == kEngineKatago) {
        fn_.katago.stop_analysis(engineHandle_);
    } else {
        fn_.leelaz.stop_analysis(engineHandle_);
    }
    currentAnalysisCallback_ = nullptr;
}

// static
void DylibGtpEngine::analysisCallbackAdapter(void* userData, const char* line) {
    auto* self = static_cast<DylibGtpEngine*>(userData);
    if (self && self->currentAnalysisCallback_ && line) {
        try {
            self->currentAnalysisCallback_(std::string(line));
        } catch (...) {}
    }
}

bool DylibGtpEngine::loadSymbols() {
    if (engineType_ == kEngineKatago) {
        #define LOAD_KATA(sym) do { \
            fn_.katago.sym = reinterpret_cast<decltype(fn_.katago.sym)>(dlsym(libHandle_, "katago_" #sym)); \
            if (!fn_.katago.sym) { LOGE("missing symbol: katago_" #sym); return false; } \
        } while(0)
        LOAD_KATA(create);
        LOAD_KATA(destroy);
        LOAD_KATA(send_command);
        LOAD_KATA(free_string);
        LOAD_KATA(start_analysis);
        LOAD_KATA(stop_analysis);
        LOAD_KATA(is_ready);
        LOAD_KATA(name);
        LOAD_KATA(version);
        #undef LOAD_KATA
        return true;
    } else {
        #define LOAD_LEELAZ(sym) do { \
            fn_.leelaz.sym = reinterpret_cast<decltype(fn_.leelaz.sym)>(dlsym(libHandle_, "leelaz_" #sym)); \
            if (!fn_.leelaz.sym) { LOGE("missing symbol: leelaz_" #sym); return false; } \
        } while(0)
        LOAD_LEELAZ(create);
        LOAD_LEELAZ(destroy);
        LOAD_LEELAZ(send_command);
        LOAD_LEELAZ(free_string);
        LOAD_LEELAZ(start_analysis);
        LOAD_LEELAZ(stop_analysis);
        LOAD_LEELAZ(is_ready);
        LOAD_LEELAZ(name);
        LOAD_LEELAZ(version);
        #undef LOAD_LEELAZ
        return true;
    }
}

} // namespace weiqi
