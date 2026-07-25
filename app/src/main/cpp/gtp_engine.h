#ifndef WEIQI_GTP_ENGINE_H
#define WEIQI_GTP_ENGINE_H

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_set>

namespace weiqi {

// 引擎类型标识，与 Kotlin 端 NativeEngineBridge.ENGINE_KATAGO / ENGINE_LEELAZERO 对应
constexpr int kEngineKatago = 1;
constexpr int kEngineLeelazero = 2;

// 流式分析回调：每收到一行引擎输出就回调一次
// 参数 line 为原始文本行（KataGo 为 JSON，LeelaZero 为 info 行）
using AnalysisCallback = std::function<void(const std::string& line)>;

/**
 * GTP 引擎抽象基类。
 *
 * 封装与围棋引擎（KataGo / LeelaZero）交互的统一接口。
 * 子类可通过两种方式接入真实引擎：
 *
 * 1. 子进程方式（推荐）：
 *    通过 popen / fork+exec 启动引擎二进制（katago / leelaz），
 *    通过 stdin/stdout 收发 GTP 命令。优点是与引擎版本解耦，
 *    缺点是 Android 上需打包二进制并处理权限。
 *
 * 2. 动态库方式：
 *    通过 dlopen 加载 libkatago.so / libleelaz.so，
 *    调用其暴露的 C 接口（如 KataGo 的 `katago_main` 或自定义 bridge）。
 *    优点是性能更好，缺点是需要引擎提供稳定 C ABI。
 *
 * 默认提供的 [StubGtpEngine] 是桩实现，不依赖任何真实引擎，
 * genmove 返回随机合法点或 pass，用于 UI 调试与编译验证。
 *
 * 线程安全：每个实例内部用 mutex 串行化命令发送；
 * 流式分析在独立线程中运行，通过 callback 回调。
 */
class GtpEngine {
public:
    virtual ~GtpEngine() = default;

    // 启动引擎；成功返回 true
    virtual bool start() = 0;

    // 关闭引擎并释放资源
    virtual void shutdown() = 0;

    /**
     * 同步发送一条 GTP 命令并等待响应。
     * @param command GTP 命令字符串（不带换行符）。
     * @param errorMessage 输出参数，失败时写入错误信息。
     * @return 响应正文（已去除前缀 "=" / "?" 与结尾换行）；
     *         失败时返回空串并设置 errorMessage。
     */
    virtual std::string sendCommand(const std::string& command,
                                    std::string& errorMessage) = 0;

    /**
     * 启动流式分析。引擎在后台线程持续输出分析行，
     * 通过 [callback] 回调。调用 [stopAnalysis] 终止。
     * @param command `kata-analyze` / `lz-analyze` 命令字符串。
     * @param callback 分析行回调。
     */
    virtual void startAnalysis(const std::string& command,
                               AnalysisCallback callback) = 0;

    // 停止流式分析
    virtual void stopAnalysis() = 0;

    // 引擎是否就绪
    virtual bool isReady() const = 0;

    // 引擎显示名称
    virtual std::string name() const = 0;

    // 引擎版本
    virtual std::string version() const = 0;
};

/**
 * 桩实现：不依赖任何真实引擎。
 *
 * 行为：
 * - `boardsize` / `clear_board` / `komi` / `play` / `time_left`：返回成功 "="
 * - `name` / `version` / `protocol_version`：返回桩标识
 * - `genmove`：返回随机合法点（基于已 play 的坐标）或 "pass"
 * - `kata-analyze` / `lz-analyze`：在后台线程输出一行桩分析数据后自动停止
 *
 * 该类用于：
 * 1. UI 调试：无需真实引擎即可走通 genmove / analyze 流程
 * 2. 编译验证：确保 CMakeLists 与 JNI 桥接可正确构建
 */
class StubGtpEngine : public GtpEngine {
public:
    explicit StubGtpEngine(int engineType);
    ~StubGtpEngine() override;

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
    bool ready_;
    bool analyzing_;
    int boardSize_;
    double komi_;
    // 已落子的坐标集合（"A1".."T19" 风格字符串），用于 genmove 随机选点
    std::unordered_set<std::string> occupied_;
    mutable std::mutex mutex_;

    // 生命周期标志：析构时置 false，分析线程捕获 shared_ptr 以安全判断
    std::shared_ptr<std::atomic<bool>> alive_;

    // 桩 genmove：返回随机合法点或 pass
    std::string stubGenmove(const std::string& color);
    // 列字母（A..T 跳过 I），共 19 个
    static const char* kColsSkipI;
};

} // namespace weiqi

#endif // WEIQI_GTP_ENGINE_H
