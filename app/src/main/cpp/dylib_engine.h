#ifndef WEIQI_DYLIB_ENGINE_H
#define WEIQI_DYLIB_ENGINE_H

#include <dlfcn.h>
#include <string>

#include "gtp_engine.h"
#include "kata_bridge.h"
#include "leelaz_bridge.h"

namespace weiqi {

/**
 * 动态加载式 GTP 引擎基类。
 *
 * 通过 dlopen 加载 libkatago.so / libleelaz.so，
 * 调用其暴露的 C 接口（见 kata_bridge.h / leelaz_bridge.h）。
 *
 * 性能优于子进程方式（少一次进程间拷贝），
 * 但要求引擎 .so 导出约定的 C 函数。
 *
 * 用法：
 *   - 把编译好的 libkatago.so / libleelaz.so 放到 jniLibs/<abi>/
 *   - 启动时本类自动 dlopen 并查找符号
 *   - 符号缺失时创建失败，返回错误信息
 */
class DylibGtpEngine : public GtpEngine {
public:
    DylibGtpEngine(int engineType,
                   const std::string& libPath,
                   const std::string& configJson);
    ~DylibGtpEngine() override;

    bool start() override;
    void shutdown() override;
    std::string sendCommand(const std::string& command,
                            std::string& errorMessage) override;
    void startAnalysis(const std::string& command,
                       AnalysisCallback callback) override;
    void stopAnalysis() override;
    bool isReady() const override;
    std::string name() const override;
    std::string version() const override;

private:
    int engineType_;
    std::string libPath_;
    std::string configJson_;
    void* libHandle_ = nullptr;
    void* engineHandle_ = nullptr;

    // 已解析的函数指针
    union {
        struct {
            decltype(&katago_create) create;
            decltype(&katago_destroy) destroy;
            decltype(&katago_send_command) send_command;
            decltype(&katago_free_string) free_string;
            decltype(&katago_start_analysis) start_analysis;
            decltype(&katago_stop_analysis) stop_analysis;
            decltype(&katago_is_ready) is_ready;
            decltype(&katago_name) name;
            decltype(&katago_version) version;
        } katago;
        struct {
            decltype(&leelaz_create) create;
            decltype(&leelaz_destroy) destroy;
            decltype(&leelaz_send_command) send_command;
            decltype(&leelaz_free_string) free_string;
            decltype(&leelaz_start_analysis) start_analysis;
            decltype(&leelaz_stop_analysis) stop_analysis;
            decltype(&leelaz_is_ready) is_ready;
            decltype(&leelaz_name) name;
            decltype(&leelaz_version) version;
        } leelaz;
    } fn_;

    bool loadSymbols();
    static void analysisCallbackAdapter(void* userData, const char* line);

    AnalysisCallback currentAnalysisCallback_;
};

} // namespace weiqi

#endif // WEIQI_DYLIB_ENGINE_H
