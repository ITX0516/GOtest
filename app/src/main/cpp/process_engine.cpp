#include "process_engine.h"

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#include <chrono>
#include <sstream>
#include <thread>

#define LOG_TAG "weiqi_engine"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

namespace weiqi {

ProcessGtpEngine::ProcessGtpEngine(std::string executablePath,
                                   std::vector<std::string> args,
                                   std::string workingDir)
    : executablePath_(std::move(executablePath)),
      args_(std::move(args)),
      workingDir_(std::move(workingDir)) {}

ProcessGtpEngine::~ProcessGtpEngine() {
    shutdown();
}

// ===== 启动与停止 =====

bool ProcessGtpEngine::start() {
    {
        std::lock_guard<std::mutex> lock(commandMutex_);
        if (running_.load()) {
            return true;  // 已启动
        }

        if (!spawnProcess()) {
            return false;
        }

        running_.store(true);

        // 启动读取线程
        readThread_ = std::thread([this]() { readLoop(); });
    }

    // 等待引擎初始化（不在锁内）
    std::this_thread::sleep_for(std::chrono::milliseconds(500));

    // 检查子进程是否还活着（可能启动后立即崩溃）
    if (childPid_ > 0) {
        int status = 0;
        pid_t result = waitpid(childPid_, &status, WNOHANG);
        if (result != 0) {
            // 子进程已退出
            running_.store(false);
            childPid_ = -1;
            if (WIFEXITED(status)) {
                lastError_ = "引擎启动后立即退出 (exit code=" +
                             std::to_string(WEXITSTATUS(status)) + ")";
            } else if (WIFSIGNALED(status)) {
                lastError_ = "引擎被信号终止 (signal=" +
                             std::to_string(WTERMSIG(status)) + ")";
            }
            LOGE("engine died immediately: %s", lastError_.c_str());
            return false;
        }
    }

    // 查询版本（sendCommand 内部会抢锁，所以必须在锁外调用）
    queryVersionAfterStart();

    return true;
}

void ProcessGtpEngine::shutdown() {
    if (!running_.load()) return;

    stopAnalysis();

    // 发送 quit 命令（忽略错误）
    if (stdinFd_ >= 0) {
        const char* quit = "quit\n";
        write(stdinFd_, quit, strlen(quit));
        fsync(stdinFd_);
    }

    running_.store(false);

    // 关闭写端，让子进程自然退出
    if (stdinFd_ >= 0) {
        close(stdinFd_);
        stdinFd_ = -1;
    }

    // 等待读取线程退出
    if (readThread_.joinable()) {
        readThread_.join();
    }

    // 关闭读端
    if (stdoutFd_ >= 0) {
        close(stdoutFd_);
        stdoutFd_ = -1;
    }

    // 等子进程退出，必要时 SIGKILL
    if (childPid_ > 0) {
        int status = 0;
        pid_t waited = waitpid(childPid_, &status, WNOHANG);
        if (waited == 0) {
            // 子进程还在，给 1 秒时间优雅退出
            std::this_thread::sleep_for(std::chrono::seconds(1));
            waited = waitpid(childPid_, &status, WNOHANG);
            if (waited == 0) {
                kill(childPid_, SIGKILL);
                waitpid(childPid_, &status, 0);
            }
        }
        childPid_ = -1;
    }
}

bool ProcessGtpEngine::isReady() const {
    return running_.load() && stdinFd_ >= 0;
}

// ===== 子进程创建 =====

bool ProcessGtpEngine::spawnProcess() {
    int stdinPipe[2];   // [0]=读(子进程), [1]=写(父)
    int stdoutPipe[2];  // [0]=读(父), [1]=写(子进程)
    int execErrPipe[2]; // 用于检测 execvp 是否失败

    if (pipe(stdinPipe) != 0 || pipe(stdoutPipe) != 0 || pipe(execErrPipe) != 0) {
        LOGE("pipe() failed: %s", strerror(errno));
        return false;
    }

    // 设置 execErrPipe 写端为 close-on-exec
    // execvp 成功时该 fd 自动关闭，父进程读到 EOF；失败时子进程写入 errno
    fcntl(execErrPipe[1], F_SETFD, FD_CLOEXEC);

    pid_t pid = fork();
    if (pid < 0) {
        LOGE("fork() failed: %s", strerror(errno));
        close(stdinPipe[0]); close(stdinPipe[1]);
        close(stdoutPipe[0]); close(stdoutPipe[1]);
        close(execErrPipe[0]); close(execErrPipe[1]);
        return false;
    }

    if (pid == 0) {
        // ===== 子进程 =====
        // 重定向 stdin / stdout / stderr
        dup2(stdinPipe[0], STDIN_FILENO);
        dup2(stdoutPipe[1], STDOUT_FILENO);
        dup2(stdoutPipe[1], STDERR_FILENO);

        // 关闭不需要的 pipe 端
        close(stdinPipe[0]); close(stdinPipe[1]);
        close(stdoutPipe[0]); close(stdoutPipe[1]);
        // execErrPipe[0] 已在父进程关闭，execErrPipe[1] 设了 CLOEXEC

        // 切换工作目录
        if (!workingDir_.empty()) {
            if (chdir(workingDir_.c_str()) != 0) {
                // chdir 失败不致命，继续执行
            }
        }

        // 构造 argv：[executable, arg1, arg2, ..., nullptr]
        std::vector<const char*> argv;
        argv.reserve(args_.size() + 2);
        argv.push_back(executablePath_.c_str());
        for (const auto& a : args_) {
            argv.push_back(a.c_str());
        }
        argv.push_back(nullptr);

        execvp(executablePath_.c_str(), const_cast<char* const*>(argv.data()));

        // execvp 失败：写入 errno 让父进程知道
        int err = errno;
        write(execErrPipe[1], &err, sizeof(err));
        _exit(127);
    }

    // ===== 父进程 =====
    childPid_ = pid;
    stdinFd_ = stdinPipe[1];   // 父进程写
    stdoutFd_ = stdoutPipe[0]; // 父进程读

    // 关闭子进程端
    close(stdinPipe[0]);
    close(stdoutPipe[1]);
    close(execErrPipe[1]);

    // 检测 execvp 是否成功：读 execErrPipe[0]
    // 如果 execvp 成功，fd 被 CLOEXEC 关闭，read 返回 0 (EOF)
    // 如果 execvp 失败，子进程写入了 errno，read 返回 sizeof(int)
    int execErrno = 0;
    ssize_t errRead = read(execErrPipe[0], &execErrno, sizeof(execErrno));
    close(execErrPipe[0]);

    if (errRead > 0) {
        // execvp 失败
        LOGE("execvp(%s) failed: %s", executablePath_.c_str(), strerror(execErrno));
        // 回收子进程
        int status = 0;
        waitpid(pid, &status, 0);
        childPid_ = -1;
        stdinFd_ = -1;
        stdoutFd_ = -1;
        lastError_ = std::string("无法启动引擎: ") + strerror(execErrno) +
                     " (路径: " + executablePath_ + ")";
        return false;
    }

    // 设置为非阻塞读，避免读取线程卡死
    int flags = fcntl(stdoutFd_, F_GETFL, 0);
    fcntl(stdoutFd_, F_SETFL, flags | O_NONBLOCK);

    LOGI("spawned engine pid=%d, exec=%s", pid, executablePath_.c_str());
    return true;
}

// ===== 同步命令 =====

std::string ProcessGtpEngine::sendCommand(const std::string& command,
                                          std::string& errorMessage) {
    errorMessage.clear();

    std::unique_lock<std::mutex> cmdLock(commandMutex_);
    if (!running_.load() || stdinFd_ < 0) {
        errorMessage = "engine not running";
        return "";
    }

    // 清空上一次响应
    {
        std::lock_guard<std::mutex> respLock(responseMutex_);
        currentResponse_.clear();
        currentError_.clear();
        responseReady_ = false;
        responseBuffer_.clear();
        inResponse_ = false;
        responseIsError_ = false;
    }

    // 发送命令（带换行符）
    std::string cmdWithNewline = command + "\n";
    ssize_t written = write(stdinFd_, cmdWithNewline.c_str(), cmdWithNewline.size());
    if (written < 0) {
        // Broken pipe = 子进程已退出
        if (errno == EPIPE) {
            errorMessage = "引擎已退出（Broken pipe）。" +
                          (lastError_.empty() ? std::string("可能原因：二进制路径不可执行(W^X)、权重文件缺失、配置文件错误") : lastError_);
        } else {
            errorMessage = std::string("write failed: ") + strerror(errno);
        }
        running_.store(false);
        return "";
    }
    fsync(stdinFd_);

    // 等待响应（最多 120 秒，分析类命令可能更久但分析不走 sendCommand）
    std::unique_lock<std::mutex> respLock(responseMutex_);
    bool got = responseCv_.wait_for(
        respLock, std::chrono::seconds(120), [this] { return responseReady_; });

    if (!got) {
        errorMessage = "command timed out";
        return "";
    }

    if (responseIsError_) {
        errorMessage = currentResponse_;
        return "";
    }

    return currentResponse_;
}

// ===== 流式分析 =====

void ProcessGtpEngine::startAnalysis(const std::string& command,
                                     AnalysisCallback callback) {
    stopAnalysis();

    std::lock_guard<std::mutex> lock(analysisMutex_);
    analysisCallback_ = std::move(callback);
    analyzing_.store(true);

    // 直接通过 stdin 发送分析命令（不使用 sendCommand，因为是流式的）
    if (stdinFd_ >= 0) {
        std::string cmd = command + "\n";
        write(stdinFd_, cmd.c_str(), cmd.size());
        fsync(stdinFd_);
    }
}

void ProcessGtpEngine::stopAnalysis() {
    if (!analyzing_.load()) return;

    // 向引擎发送中断信号（GTP 标准：发送一空行或 Ctrl-C）
    // KataGo/LeelaZero 支持在 stdin 发送空行打断分析
    if (stdinFd_ >= 0) {
        const char* interrupt = "\n";
        write(stdinFd_, interrupt, 1);
        fsync(stdinFd_);
    }

    analyzing_.store(false);
    std::lock_guard<std::mutex> lock(analysisMutex_);
    analysisCallback_ = nullptr;
}

// ===== 读取线程 =====

void ProcessGtpEngine::readLoop() {
    std::string lineBuffer;
    char buf[4096];

    while (running_.load()) {
        if (stdoutFd_ < 0) break;

        ssize_t n = read(stdoutFd_, buf, sizeof(buf) - 1);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                // 非阻塞，等一会儿再读
                std::this_thread::sleep_for(std::chrono::milliseconds(20));
                continue;
            }
            // 读错误
            break;
        }
        if (n == 0) {
            // EOF（子进程退出）
            break;
        }

        buf[n] = '\0';

        // 按行解析
        for (ssize_t i = 0; i < n; ++i) {
            char c = buf[i];
            if (c == '\n' || c == '\r') {
                if (!lineBuffer.empty()) {
                    handleResponseLine(lineBuffer);
                    lineBuffer.clear();
                }
                if (c == '\n') {
                    // 空行：GTP 响应结束标记
                    if (inResponse_ && responseBuffer_.empty()) {
                        // 响应正文结束
                        inResponse_ = false;
                        std::lock_guard<std::mutex> respLock(responseMutex_);
                        responseReady_ = true;
                        responseCv_.notify_one();
                    }
                }
            } else {
                lineBuffer.push_back(c);
            }
        }
    }
}

void ProcessGtpEngine::handleResponseLine(const std::string& line) {
    if (line.empty()) return;

    // 分析模式：所有行都走回调
    if (analyzing_.load()) {
        std::lock_guard<std::mutex> lock(analysisMutex_);
        if (analysisCallback_) {
            try {
                analysisCallback_(line);
            } catch (...) {
                // 回调异常不影响读线程
            }
        }
        return;
    }

    // GTP 响应格式：
    //   = <response>  （成功）
    //   ? <error>     （失败）
    //   多行响应以空行结尾
    if (!inResponse_) {
        if (line[0] == '=') {
            inResponse_ = true;
            responseIsError_ = false;
            responseBuffer_.clear();
            // 去掉 "= " 前缀
            size_t start = 1;
            if (line.size() > 1 && line[1] == ' ') start = 2;
            responseBuffer_ = line.substr(start);
            return;
        } else if (line[0] == '?') {
            inResponse_ = true;
            responseIsError_ = true;
            responseBuffer_.clear();
            size_t start = 1;
            if (line.size() > 1 && line[1] == ' ') start = 2;
            responseBuffer_ = line.substr(start);
            return;
        }
        // 不属于当前响应的输出（引擎初始化日志等），忽略
        LOGD("engine stdout: %s", line.c_str());
        return;
    }

    // 已在响应中，追加行
    if (!responseBuffer_.empty()) responseBuffer_ += '\n';
    responseBuffer_ += line;
}

// ===== 版本查询 =====

void ProcessGtpEngine::queryVersionAfterStart() {
    // 注意：此函数在 start() 中被调用，commandMutex_ 已被 start() 持有
    // 因此我们用局部变量调用私有版本（不抢锁）
    std::string err;

    // name 命令
    std::string cmd = "name";
    // 直接走管道通信：这里简化为直接使用 sendCommand
    // 但 sendCommand 会抢锁，所以我们用一个简单版本
    // 实际这里通过释放-重入方式更安全，但为简化我们直接读
    auto nameResp = sendCommand(cmd, err);
    if (!err.empty()) {
        LOGW("query name failed: %s", err.c_str());
    } else if (!nameResp.empty()) {
        name_ = nameResp;
    }

    auto verResp = sendCommand("version", err);
    if (!err.empty()) {
        LOGW("query version failed: %s", err.c_str());
    } else if (!verResp.empty()) {
        version_ = verResp;
    }
}

// 因为 sendCommand 在 queryVersionAfterStart 中被调用，但 sendCommand 会抢 commandMutex_
// 而 start() 已经持有该锁，所以会死锁。
// 解决方案：在 start() 中调用 sendCommand 之前先释放锁，或者把 queryVersion 放在锁外。
// 这里用更简单的办法：把 queryVersionAfterStart 改成在锁外调用。
// 修改 start() 逻辑：启动过程中先建立进程与读线程，然后解锁，再查询版本。

} // namespace weiqi
