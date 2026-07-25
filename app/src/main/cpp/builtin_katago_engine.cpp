// ============================================================================
// BuiltinKataGoEngine 实现
//
// 直接链接 KataGo 源码，使用 KataGo 的原生 C++ API。
// 只在 WEIQI_BUILTIN_MODE 定义时编译。
//
// 实现说明：
//   KataGo 的 GTPEngine 类（cpp/command/gtp.cpp）不是公开头文件，
//   直接调用它的构造函数很复杂（需要 Config、Logger、NNEvaluator 等多个依赖）。
//   因此本实现采用更底层的方式：
//   1. 用 KataGo 的 NNEvaluator 加载神经网络
//   2. 用 KataGo 的 AsyncBot 执行搜索
//   3. 自己实现 GTP 命令解析（只实现核心命令）
//
//   这样做的好处是不依赖 KataGo 的内部命令行架构，
//   只依赖它的公开 API（game/、search/、neuralnet/ 目录下的类）。
//
// 注意：本文件中的 KataGo API 调用基于 v1.16.x 版本。
//       如果 KataGo API 发生变化，需要相应调整。
// ============================================================================

#ifdef WEIQI_BUILTIN_MODE

#include "builtin_katago_engine.h"

#include <sstream>
#include <cstring>
#include <algorithm>
#include <android/log.h>

#define LOG_TAG "BuiltinKataGo"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// KataGo 头文件（基于 KataGo v1.16.x 的真实目录结构）
// 这些路径假设 KataGo 源码在 third_party/katago/cpp/ 下
#include "game/board.h"
#include "game/boardhistory.h"
#include "game/rules.h"
#include "neuralnet/nneval.h"
#include "neuralnet/nninputs.h"
#include "search/asyncbot.h"
#include "search/searchparams.h"
#include "dataio/loadmodel.h"
#include "dataio/numpywrite.h"
#include "core/logger.h"
#include "core/config_parser.h"

namespace weiqi {

// ===== KataGoEngineImpl：封装 KataGo 原生对象 =====
struct BuiltinKataGoEngine::KataGoEngineImpl {
    // KataGo 核心对象
    std::unique_ptr<Logger> logger;
    std::unique_ptr<NNEvaluator> nnEval;
    std::unique_ptr<AsyncBot> bot;
    SearchParams searchParams;

    // 游戏状态
    Board board;
    BoardHistory hist;
    Player nextPla;  // C_BLACK or C_WHITE

    // 配置
    Rules rules;
    double komi;
    int boardSize;

    // 锁
    std::mutex engineMutex;
};

// ===== 辅助函数 =====

// 将 GTP 坐标（如 "D4", "Q16", "pass"）转为 KataGo 的 Loc
static Loc gtpToLoc(const std::string& s, int boardXSize, int boardYSize) {
    if (s == "pass" || s == "PASS" || s == "Pass") return Board::PASS_LOC;
    if (s == "resign" || s == "RESIGN" || s == "Resign") return Board::RESIGN_LOC;

    if (s.size() < 2) return Board::NULL_LOC;

    // GTP 坐标：列字母（跳过 I）+ 行号
    char colChar = toupper(s[0]);
    if (colChar < 'A' || colChar > 'Z') return Board::NULL_LOC;

    int col = colChar - 'A';
    if (colChar > 'I') col--;  // GTP 跳过 'I'

    std::string rowStr = s.substr(1);
    int row;
    try { row = std::stoi(rowStr); } catch (...) { return Board::NULL_LOC; }
    row--;  // GTP 行号从 1 开始

    if (col < 0 || col >= boardXSize || row < 0 || row >= boardYSize) {
        return Board::NULL_LOC;
    }

    return Location::getLoc(col, row, boardXSize);
}

// 将 KataGo 的 Loc 转为 GTP 坐标字符串
static std::string locToGtp(Loc loc, int boardXSize, int boardYSize) {
    if (loc == Board::PASS_LOC) return "pass";
    if (loc == Board::RESIGN_LOC) return "resign";
    if (loc == Board::NULL_LOC) return "invalid";

    int col = Location::getX(loc, boardXSize);
    int row = Location::getY(loc, boardXSize);

    std::string result;
    char colChar = 'A' + col;
    if (colChar >= 'I') colChar++;  // GTP 跳过 'I'
    result += colChar;
    result += std::to_string(row + 1);
    return result;
}

// ===== BuiltinKataGoEngine =====

BuiltinKataGoEngine::BuiltinKataGoEngine(Config config)
    : config_(std::move(config)),
      impl_(std::make_unique<KataGoEngineImpl>())
{
    version_ = "builtin-1.16.x";
}

BuiltinKataGoEngine::~BuiltinKataGoEngine()
{
    shutdown();
}

bool BuiltinKataGoEngine::start()
{
    std::lock_guard<std::mutex> lock(commandMutex_);

    if (ready_.load()) {
        return true;
    }

    LOGI("Starting builtin KataGo engine...");
    LOGI("  weights: %s", config_.weightsPath.c_str());
    LOGI("  threads: %d", config_.threads);
    LOGI("  boardSize: %d", config_.boardSize);
    LOGI("  cpuOnly: %d", config_.cpuOnly ? 1 : 0);

    try {
        // 1. 创建 Logger
        impl_->logger = std::make_unique<Logger>();
        impl_->logger->setLogToStdout(false);
        impl_->logger->setLogToStderr(false);

        // 2. 设置规则（中国规则）
        impl_->komi = config_.komi;
        impl_->boardSize = config_.boardSize;
        impl_->rules = Rules::parseRules("chinese");
        impl_->rules.komi = config_.komi;

        // 3. 初始化棋盘
        impl_->board = Board(config_.boardSize, config_.boardSize);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;

        // 4. 加载神经网络模型
        // KataGo 的 NNEvaluator 创建比较复杂，需要通过 LoadModel
        std::vector<int> gpuIdx;
        gpuIdx.push_back(0);

        // 创建 NNEvaluator
        // 注意：这里使用 CPU-only 模式（Eigen 后端）
        // 如果要支持 GPU，需要改用 OpenCL 后端
        impl_->nnEval = NNEvaluator::create(
            config_.weightsPath,
            config_.weightsPath,  // 模型文件名（与路径相同）
            *impl_->logger,
            config_.threads,       // nNewEvals
            gpuIdx,
            config_.boardSize,     // nnXLen
            config_.boardSize,     // nnYLen
            false,                 // isV3Lookup
            true,                  // skipNeuralNetCache
            config_.cpuOnly        // cpuOnly (Eigen backend)
        );

        if (!impl_->nnEval) {
            LOGE("Failed to create NNEvaluator");
            return false;
        }

        // 设置 NN 缓存
        impl_->nnEval->setNumThreads(config_.threads);

        // 5. 配置搜索参数
        impl_->searchParams = SearchParams();
        impl_->searchParams.maxVisits = config_.maxVisits;
        impl_->searchParams.numSearchThreads = config_.threads;
        impl_->searchParams.nnCacheSizePowerOfTwo = config_.nnCacheSizePowerOfTwo;

        // 6. 创建 AsyncBot
        impl_->bot = std::make_unique<AsyncBot>(
            impl_->searchParams,
            impl_->nnEval.get(),
            impl_->logger.get(),
            impl_->rules,
            impl_->komi
        );

        // 设置初始局面
        impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);

        ready_.store(true);
        LOGI("Builtin KataGo engine ready (version: %s)", version_.c_str());
        return true;

    } catch (const std::exception& e) {
        LOGE("Exception starting KataGo: %s", e.what());
        return false;
    } catch (...) {
        LOGE("Unknown exception starting KataGo");
        return false;
    }
}

void BuiltinKataGoEngine::shutdown()
{
    if (shutdown_.exchange(true)) {
        return;
    }

    stopAnalysis();

    if (analysisThread_.joinable()) {
        analysisThread_.join();
    }

    std::lock_guard<std::mutex> lock(commandMutex_);
    impl_->bot.reset();
    impl_->nnEval.reset();
    impl_->logger.reset();
    ready_.store(false);

    LOGI("Builtin KataGo engine shut down");
}

std::string BuiltinKataGoEngine::sendCommand(
    const std::string& command,
    std::string& errorMessage)
{
    std::lock_guard<std::mutex> lock(commandMutex_);

    if (!ready_.load()) {
        errorMessage = "Engine not started";
        return "";
    }

    try {
        return handleGtpCommand(command, errorMessage);
    } catch (const std::exception& e) {
        errorMessage = std::string("Exception: ") + e.what();
        LOGE("%s", errorMessage.c_str());
        return "";
    } catch (...) {
        errorMessage = "Unknown exception";
        LOGE("%s", errorMessage.c_str());
        return "";
    }
}

// ===== GTP 命令处理 =====

std::string BuiltinKataGoEngine::handleGtpCommand(
    const std::string& command,
    std::string& errorMessage)
{
    // 解析命令
    std::istringstream iss(command);
    std::string cmd;
    iss >> cmd;

    // 转小写
    std::transform(cmd.begin(), cmd.end(), cmd.begin(), ::tolower);

    if (cmd == "protocol_version") {
        return "2";
    }
    if (cmd == "name") {
        return "KataGo";
    }
    if (cmd == "version") {
        return version_;
    }
    if (cmd == "list_commands") {
        return "protocol_version\nname\nversion\nlist_commands\n"
               "boardsize\nclear_board\nkomi\nplay\ngenmove\n"
               "kata-analyze\nshowboard";
    }
    if (cmd == "known_command") {
        std::string name;
        iss >> name;
        static const std::vector<std::string> known = {
            "protocol_version", "name", "version", "list_commands",
            "known_command", "boardsize", "clear_board", "komi",
            "play", "genmove", "showboard", "kata-analyze"
        };
        bool found = std::find(known.begin(), known.end(), name) != known.end();
        return found ? "true" : "false";
    }
    if (cmd == "boardsize") {
        int size;
        iss >> size;
        if (size < 2 || size > 19) {
            errorMessage = "unacceptable size";
            return "";
        }
        impl_->boardSize = size;
        impl_->board = Board(size, size);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;
        if (impl_->bot) {
            impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        }
        return "";
    }
    if (cmd == "clear_board") {
        impl_->board = Board(impl_->boardSize, impl_->boardSize);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;
        if (impl_->bot) {
            impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        }
        return "";
    }
    if (cmd == "komi") {
        double komi;
        iss >> komi;
        impl_->komi = komi;
        impl_->rules.komi = komi;
        if (impl_->bot) {
            impl_->bot->setKomiIfNoRepl(komi);
        }
        return "";
    }
    if (cmd == "play") {
        std::string color, vertex;
        iss >> color >> vertex;
        Player pla = (color[0] == 'w' || color[0] == 'W') ? C_WHITE : C_BLACK;
        Loc loc = gtpToLoc(vertex, impl_->boardSize, impl_->boardSize);
        if (loc == Board::NULL_LOC) {
            errorMessage = "illegal move";
            return "";
        }
        impl_->hist.makeBoardMoveAssumeLegal(impl_->board, loc, pla, nullptr);
        impl_->nextPla = getOpp(pla);
        if (impl_->bot) {
            impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        }
        return "";
    }
    if (cmd == "genmove") {
        std::string color;
        iss >> color;
        Player pla = (color[0] == 'w' || color[0] == 'W') ? C_WHITE : C_BLACK;

        if (!impl_->bot) {
            errorMessage = "bot not initialized";
            return "";
        }

        // 执行搜索
        Loc loc = impl_->bot->genMoveSynchronous(pla);

        if (loc == Board::RESIGN_LOC) return "resign";
        if (loc == Board::PASS_LOC) return "pass";

        // 应用落子
        impl_->hist.makeBoardMoveAssumeLegal(impl_->board, loc, pla, nullptr);
        impl_->nextPla = getOpp(pla);
        impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);

        return locToGtp(loc, impl_->boardSize, impl_->boardSize);
    }
    if (cmd == "showboard") {
        std::ostringstream oss;
        // 简化输出
        oss << "Board " << impl_->boardSize << "x" << impl_->boardSize;
        return oss.str();
    }
    if (cmd == "kata-analyze" || cmd == "lz-analyze") {
        // 分析模式由 startAnalysis/analysisLoop 处理
        // 这里返回当前分析结果
        return runAnalysisOnce();
    }
    if (cmd == "quit") {
        shutdown_ = true;
        return "";
    }

    errorMessage = "unknown command: " + cmd;
    return "";
}

std::string BuiltinKataGoEngine::runAnalysisOnce()
{
    if (!impl_->bot) return "";

    // 执行搜索并获取分析数据
    // KataGo 的 AsyncBot 支持获取分析 JSON
    Loc loc = impl_->bot->genMoveSynchronous(impl_->nextPla);

    // 简化：返回基本的 JSON 格式分析结果
    std::ostringstream json;
    json << "{\"move\":\"" << locToGtp(loc, impl_->boardSize, impl_->boardSize) << "\"";
    json << ",\"winrate\":0.5";
    json << ",\"visits\":" << config_.maxVisits;
    json << "}";
    return json.str();
}

// ===== 流式分析 =====

void BuiltinKataGoEngine::startAnalysis(
    const std::string& command,
    AnalysisCallback callback)
{
    if (!ready_.load()) {
        return;
    }

    stopAnalysis();

    analysisCommand_ = command;
    analysisCallback_ = std::move(callback);
    analysisRunning_.store(true);

    analysisThread_ = std::thread(&BuiltinKataGoEngine::analysisLoop, this);
}

void BuiltinKataGoEngine::stopAnalysis()
{
    if (!analysisRunning_.exchange(false)) {
        return;
    }

    {
        std::lock_guard<std::mutex> lock(impl_->engineMutex);
        if (impl_->bot) {
            impl_->bot->stopWithoutWait();
        }
    }

    if (analysisThread_.joinable()) {
        analysisThread_.join();
    }
}

void BuiltinKataGoEngine::analysisLoop()
{
    LOGI("Analysis thread started");

    try {
        // 简化实现：周期性执行搜索并回调结果
        // 真正的流式分析需要接入 KataGo 的搜索回调
        while (analysisRunning_.load() && !shutdown_.load()) {
            std::string result = runAnalysisOnce();
            if (!result.empty() && analysisCallback_) {
                analysisCallback_(result);
            }

            // 等待一段时间再继续
            // 真正的实现应该基于搜索进度回调
            std::this_thread::sleep_for(
                std::chrono::milliseconds(100));
        }
    } catch (const std::exception& e) {
        LOGE("Analysis thread exception: %s", e.what());
    } catch (...) {
        LOGE("Analysis thread unknown exception");
    }

    analysisRunning_.store(false);
    LOGI("Analysis thread stopped");
}

// ===== sendCommandLocked（保留用于 start() 中的版本查询） =====
std::string BuiltinKataGoEngine::sendCommandLocked(
    const std::string& command,
    std::string& errorMessage)
{
    return handleGtpCommand(command, errorMessage);
}

} // namespace weiqi

#endif // WEIQI_BUILTIN_MODE
