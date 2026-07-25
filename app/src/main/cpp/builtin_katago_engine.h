// ============================================================================
// BuiltinKataGoEngine: 直接把 KataGo 编译进 .so 的引擎实现
//
// 这是 BUILTIN 模式的核心：
//   - 不依赖外部可执行文件或 .so
//   - 直接链接 KataGo 源码到 libweiqi_engine.so
//   - 通过 KataGo 原生 C++ API（GTP 引擎类）通信
//
// 注意：本文件只在 WEIQI_BUILTIN_MODE 定义时参与编译。
// ============================================================================

#ifdef WEIQI_BUILTIN_MODE

#ifndef WEIQI_BUILTIN_KATAGO_ENGINE_H
#define WEIQI_BUILTIN_KATAGO_ENGINE_H

#include "gtp_engine.h"
#include <string>
#include <memory>
#include <thread>
#include <mutex>
#include <atomic>
#include <condition_variable>
#include <chrono>

namespace weiqi {

// 前向声明，避免包含 KataGo 重型头文件
class KataGoEngineImpl;

class BuiltinKataGoEngine : public GtpEngine {
public:
    struct Config {
        std::string weightsPath;
        std::string configPath;     // 可选，为空则用默认配置
        int threads = 2;
        int maxVisits = 800;
        double komi = 7.5;
        int boardSize = 19;
        bool cpuOnly = true;
        bool enablePonder = false;
        int nnCacheSizePowerOfTwo = 17;
    };

    explicit BuiltinKataGoEngine(Config config);
    ~BuiltinKataGoEngine() override;

    bool start() override;
    void shutdown() override;

    std::string sendCommand(const std::string& command,
                            std::string& errorMessage) override;

    void startAnalysis(const std::string& command,
                       AnalysisCallback callback) override;
    void stopAnalysis() override;

    bool isReady() const override { return ready_.load(); }
    std::string name() const override { return "KataGo"; }
    std::string version() const override { return version_; }

private:
    Config config_;
    std::string version_;
    std::atomic<bool> ready_{false};
    std::atomic<bool> shutdown_{false};

    // 分析线程
    std::thread analysisThread_;
    std::atomic<bool> analysisRunning_{false};
    AnalysisCallback analysisCallback_;
    std::string analysisCommand_;

    // KataGo 实现句柄（用 PIMPL 模式，避免暴露 KataGo 头文件）
    std::unique_ptr<KataGoEngineImpl> impl_;

    // 命令互斥锁
    std::mutex commandMutex_;

    // GTP 命令处理（内部实现）
    std::string handleGtpCommand(const std::string& command,
                                  std::string& errorMessage);
    std::string sendCommandLocked(const std::string& command,
                                   std::string& errorMessage);
    std::string runAnalysisOnce();

    void analysisLoop();
};

} // namespace weiqi

#endif // WEIQI_BUILTIN_KATAGO_ENGINE_H

#endif // WEIQI_BUILTIN_MODE
