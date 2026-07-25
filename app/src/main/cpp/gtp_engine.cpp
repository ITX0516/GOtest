#include "gtp_engine.h"

#include <chrono>
#include <random>
#include <sstream>
#include <thread>
#include <vector>

namespace weiqi {

// 列字母（A..T 跳过 I），与 Kotlin 端 Vertex.COLS_SKIP_I 一致
const char* StubGtpEngine::kColsSkipI = "ABCDEFGHJKLMNOPRST";

// ===== 构造与析构 =====

StubGtpEngine::StubGtpEngine(int engineType)
    : engineType_(engineType),
      ready_(false),
      analyzing_(false),
      boardSize_(19),
      komi_(7.5),
      alive_(std::make_shared<std::atomic<bool>>(true)) {}

StubGtpEngine::~StubGtpEngine() {
    // 先标记为已销毁，让 detach 的分析线程不再访问实例成员
    alive_->store(false);
    shutdown();
}

// ===== 生命周期 =====

bool StubGtpEngine::start() {
    std::lock_guard<std::mutex> lock(mutex_);
    ready_ = true;
    return true;
}

void StubGtpEngine::shutdown() {
    stopAnalysis();
    std::lock_guard<std::mutex> lock(mutex_);
    ready_ = false;
    occupied_.clear();
}

bool StubGtpEngine::isReady() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return ready_;
}

std::string StubGtpEngine::name() const {
    switch (engineType_) {
        case kEngineKatago: return "KataGo (stub)";
        case kEngineLeelazero: return "LeelaZero (stub)";
        default: return "Unknown (stub)";
    }
}

std::string StubGtpEngine::version() const {
    return "0.0.0-stub";
}

// ===== GTP 命令处理 =====

std::string StubGtpEngine::sendCommand(const std::string& command,
                                       std::string& errorMessage) {
    errorMessage.clear();
    std::lock_guard<std::mutex> lock(mutex_);
    if (!ready_) {
        errorMessage = "engine not started";
        return "";
    }

    // 解析命令首词
    std::istringstream iss(command);
    std::string cmd;
    iss >> cmd;

    // GTP 成功响应格式： "= <body>\n\n"，这里返回 body（无前缀）
    // Kotlin 端 NativeEngineBridge.sendGtpCommand 已约定：成功返回 body，
    // 失败返回 "error: <msg>"
    if (cmd == "protocol_version") {
        return "2";
    }
    if (cmd == "name") {
        return name();
    }
    if (cmd == "version") {
        return version();
    }
    if (cmd == "boardsize") {
        int n = 0;
        iss >> n;
        if (n > 0 && n <= 25) {
            boardSize_ = n;
            occupied_.clear();
        }
        return "";
    }
    if (cmd == "clear_board") {
        occupied_.clear();
        return "";
    }
    if (cmd == "komi") {
        double k = 0.0;
        iss >> k;
        komi_ = k;
        return "";
    }
    if (cmd == "play") {
        // play <color> <vertex>
        std::string color, vertex;
        iss >> color >> vertex;
        if (!vertex.empty() && vertex != "pass" && vertex != "resign") {
            occupied_.insert(vertex);
        }
        return "";
    }
    if (cmd == "time_left") {
        // time_left <color> <seconds>，桩忽略
        return "";
    }
    if (cmd == "genmove") {
        std::string color;
        iss >> color;
        return stubGenmove(color);
    }
    if (cmd == "kata-analyze" || cmd == "lz-analyze" || cmd == "lz-hint") {
        // 流式命令在此处不处理（应由 startAnalysis 调用），
        // 直接返回空表示无同步输出
        return "";
    }
    if (cmd == "quit") {
        ready_ = false;
        return "";
    }
    // 未知命令：返回成功空响应，避免阻塞
    return "";
}

// ===== 流式分析 =====

void StubGtpEngine::startAnalysis(const std::string& command,
                                  AnalysisCallback callback) {
    stopAnalysis();

    // 从命令中解析行棋方颜色（桩不使用，仅占位）
    std::istringstream iss(command);
    std::string cmd, color;
    iss >> cmd >> color;
    (void)color;

    // 在锁内快照线程所需的所有状态（不捕获 this，避免析构后悬垂）
    int engineType;
    int boardSize;
    std::vector<std::string> emptyPoints;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        analyzing_ = true;
        engineType = engineType_;
        boardSize = boardSize_;
        for (int x = 0; x < boardSize && x < 19; ++x) {
            for (int y = 0; y < boardSize; ++y) {
                std::string coord = std::string(1, kColsSkipI[x]) +
                                    std::to_string(boardSize - y);
                if (occupied_.find(coord) == occupied_.end()) {
                    emptyPoints.push_back(coord);
                }
            }
        }
    }

    // 捕获 shared_ptr<alive> 而非 this：析构后仍可安全判断
    auto alive = alive_;
    std::thread([engineType, emptyPoints, callback, alive]() {
        // 等待一小段时间模拟计算
        std::this_thread::sleep_for(std::chrono::milliseconds(50));

        // 析构后不再回调
        if (!alive->load() || !callback) return;

        // 选一个随机空点作为建议
        static thread_local std::mt19937 rng(std::random_device{}());
        std::string bestMove = "pass";
        if (!emptyPoints.empty()) {
            std::uniform_int_distribution<size_t> pick(0, emptyPoints.size() - 1);
            bestMove = emptyPoints[pick(rng)];
        }

        std::string line;
        if (engineType == kEngineKatago) {
            // KataGo JSON 格式（kata-analyze json 1）
            line = "{\"rootInfo\":{\"winrate\":0.52,\"scoreLead\":1.5,\"visits\":800},"
                   "\"moveInfos\":[{\"move\":\"" + bestMove +
                   "\",\"winrate\":0.52,\"scoreLead\":1.5,\"visits\":800,"
                   "\"pv\":[\"" + bestMove + "\"]}]}";
        } else {
            // LeelaZero 文本格式（lz-analyze）
            line = "info move " + bestMove +
                   " visits 800 winrate 52 prior 0.5 pv " + bestMove;
        }

        try {
            callback(line);
        } catch (...) {
            // 回调异常不应影响 native 线程
        }
    }).detach();
}

void StubGtpEngine::stopAnalysis() {
    std::lock_guard<std::mutex> lock(mutex_);
    analyzing_ = false;
}

// ===== 桩实现辅助 =====

std::string StubGtpEngine::stubGenmove(const std::string& color) {
    // 收集所有空点
    std::vector<std::string> candidates;
    candidates.reserve(boardSize_ * boardSize_);
    for (int x = 0; x < boardSize_ && x < 19; ++x) {
        for (int y = 0; y < boardSize_; ++y) {
            // GTP 坐标：列字母 + 行号(1..boardSize)，行号 = boardSize - y
            std::string coord = std::string(1, kColsSkipI[x]) +
                                std::to_string(boardSize_ - y);
            if (occupied_.find(coord) == occupied_.end()) {
                candidates.push_back(coord);
            }
        }
    }

    // 10% 概率 pass（模拟终局）
    static thread_local std::mt19937 rng(std::random_device{}());
    std::uniform_real_distribution<double> dist(0.0, 1.0);
    if (candidates.empty() || dist(rng) < 0.1) {
        return "pass";
    }

    std::uniform_int_distribution<size_t> pick(0, candidates.size() - 1);
    std::string chosen = candidates[pick(rng)];
    occupied_.insert(chosen);
    return chosen;
}

} // namespace weiqi
