#ifndef WEIQI_PROCESS_ENGINE_H
#define WEIQI_PROCESS_ENGINE_H

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "gtp_engine.h"

namespace weiqi {

/**
 * 子进程式 GTP 引擎基类。
 *
 * 通过 fork+exec（或 posix_spawn）启动引擎可执行文件（katago / leelaz），
 * 通过一对 pipe 连接 stdin/stdout 收发 GTP 命令。这是 KataGo/LeelaZero
 * 官方推荐的集成方式，不需要修改引擎源码，与引擎版本解耦。
 *
 * Android 部署：
 *   1. 将引擎可执行文件（如 katago）放到 app/src/main/assets/ 下；
 *   2. 应用启动时 EngineManager 从 assets 复制到 filesDir/bin/ 并 chmod +x；
 *   3. 通过命令行参数（-model / -config）指定权重与配置文件路径；
 *   4. 本类通过 execvp 启动该可执行文件。
 *
 * 线程安全：sendCommand 内部串行化（mutex）；
 * 流式分析在独立读取线程中运行，通过 callback 回调。
 */
class ProcessGtpEngine : public GtpEngine {
public:
    /**
     * @param executablePath  可执行文件绝对路径（filesDir/bin/katago 等）
     * @param args            启动参数（不含可执行文件名本身）
     * @param workingDir      工作目录，传空则用应用 filesDir
     */
    ProcessGtpEngine(std::string executablePath,
                     std::vector<std::string> args,
                     std::string workingDir = "");
    ~ProcessGtpEngine() override;

    bool start() override;
    void shutdown() override;
    std::string sendCommand(const std::string& command,
                            std::string& errorMessage) override;
    void startAnalysis(const std::string& command,
                       AnalysisCallback callback) override;
    void stopAnalysis() override;
    bool isReady() const override;
    std::string name() const override { return name_; }
    std::string version() const override { return version_; }

protected:
    // 子类在构造时设置显示名与版本查询命令
    void setName(const std::string& name) { name_ = name; }
    // 启动后自动调用 name/version 命令填充 version_
    void queryVersionAfterStart();

private:
    std::string executablePath_;
    std::vector<std::string> args_;
    std::string workingDir_;
    std::string name_ = "ProcessGtpEngine";
    std::string version_ = "0.0.0";

    // 子进程与管道
    pid_t childPid_ = -1;
    int stdinFd_ = -1;   // 写端：本进程 → 子进程
    int stdoutFd_ = -1;  // 读端：子进程 stdout → 本进程
    int stderrFd_ = -1;  // 读端：子进程 stderr → 本进程

    // 同步命令互斥
    mutable std::mutex commandMutex_;

    // 读取线程与分析状态
    std::thread readThread_;
    std::atomic<bool> running_{false};
    std::atomic<bool> analyzing_{false};
    AnalysisCallback analysisCallback_;
    std::mutex analysisMutex_;

    // 同步命令等待
    std::mutex responseMutex_;
    std::condition_variable responseCv_;
    std::string currentResponse_;
    bool responseReady_ = false;
    std::string currentError_;

    // 启动子进程与管道
    bool spawnProcess();
    // 关闭管道与子进程
    void closeProcess();

    // 读取线程主循环
    void readLoop();
    // drain stderr（非阻塞，读到没数据为止），每行打日志
    void drainStderr(std::string& lineBuf);

    // 处理一行 GTP 响应（根据当前模式分发给同步等待或分析回调）
    void handleResponseLine(const std::string& line);

    // 累积响应缓冲区（GTP 响应以空行结尾）
    std::string responseBuffer_;
    bool inResponse_ = false;
    bool responseIsError_ = false;

    // execvp 失败时的错误信息
    std::string lastError_;
};

} // namespace weiqi

#endif // WEIQI_PROCESS_ENGINE_H
