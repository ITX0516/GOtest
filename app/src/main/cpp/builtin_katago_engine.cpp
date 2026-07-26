// ============================================================================
// BuiltinKataGoEngine 实现 (KataGo v1.16.4 API)
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

// KataGo v1.16.4 headers
#include "game/board.h"
#include "game/boardhistory.h"
#include "game/rules.h"
#include "neuralnet/nneval.h"
#include "neuralnet/nninputs.h"
#include "search/asyncbot.h"
#include "search/searchparams.h"
#include "search/timecontrols.h"
#include "dataio/loadmodel.h"
#include "core/logger.h"
#include "core/config_parser.h"

namespace weiqi {

// ===== KataGoEngineImpl =====
struct BuiltinKataGoEngine::KataGoEngineImpl {
    std::unique_ptr<Logger> logger;
    std::unique_ptr<NNEvaluator> nnEval;
    std::unique_ptr<AsyncBot> bot;
    SearchParams searchParams;
    TimeControls timeControls;  // KataGo v1.16.4 requires TimeControls

    Board board;
    BoardHistory hist;
    Player nextPla;

    Rules rules;
    double komi;
    int boardSize;
    bool useFP16 = false;
    bool useNHWC = false;
    std::string nnModelName;
};

// ===== GTP坐标转换 =====
static Loc gtpToLoc(const std::string& s, int boardXSize, int boardYSize) {
    if (s == "pass" || s == "PASS" || s == "Pass") return Board::PASS_LOC;
    if (s == "resign" || s == "RESIGN" || s == "Resign") return Board::NULL_LOC;
    if (s.size() < 2) return Board::NULL_LOC;

    char colChar = toupper(s[0]);
    if (colChar < 'A' || colChar > 'Z') return Board::NULL_LOC;

    int col = colChar - 'A';
    if (colChar > 'I') col--;  // skip I

    std::string rowStr = s.substr(1);
    int row;
    try { row = std::stoi(rowStr); } catch (...) { return Board::NULL_LOC; }
    row--;

    if (col < 0 || col >= boardXSize || row < 0 || row >= boardYSize) {
        return Board::NULL_LOC;
    }
    return Location::getLoc(col, row, boardXSize);
}

static std::string locToGtp(Loc loc, int boardXSize, int boardYSize) {
    if (loc == Board::PASS_LOC) return "pass";
    if (loc == Board::NULL_LOC) return "pass";
    int col = Location::getX(loc, boardXSize);
    int row = Location::getY(loc, boardXSize);
    std::string result;
    char colChar = 'A' + col;
    if (colChar >= 'I') colChar++;
    result += colChar;
    result += std::to_string(row + 1);
    return result;
}

// ===== BuiltinKataGoEngine =====
BuiltinKataGoEngine::BuiltinKataGoEngine(Config config)
    : config_(std::move(config)),
      impl_(std::make_unique<KataGoEngineImpl>()) {
    version_ = "builtin-1.16.4";
}

BuiltinKataGoEngine::~BuiltinKataGoEngine() {
    shutdown();
}

bool BuiltinKataGoEngine::start() {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (ready_.load()) return true;

    LOGI("Starting builtin KataGo engine...");
    LOGI("  weights: %s", config_.weightsPath.c_str());

    try {
        // 1. Logger
        impl_->logger = std::make_unique<Logger>();
        impl_->logger->setLogToStdout(false);
        impl_->logger->setLogToStderr(false);

        // 2. Rules (Chinese)
        impl_->komi = config_.komi;
        impl_->boardSize = config_.boardSize;
        impl_->rules = Rules::parseRules("chinese");
        impl_->rules.komi = config_.komi;

        // 3. Board
        impl_->board = Board(config_.boardSize, config_.boardSize);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;

        // 4. Load model and create NNEvaluator (KataGo v1.16.4 full constructor)
        std::vector<int> gpuIdx;
        gpuIdx.push_back(0);

        std::string expectedSha256 = "";     // skip checksum
        bool requireExactNNLen = false;      // flexible NN input size
        bool inputsUseNHWC = impl_->useNHWC;
        int nnCacheSizePowerOfTwo = config_.nnCacheSizePowerOfTwo;
        int nnMutexPoolSizePowerOfTwo = std::min(nnCacheSizePowerOfTwo, 17);
        bool debugSkipNeuralNet = false;     // disable for real engine
        std::string openCLTunerFile = "";
        std::string homeDataDirOverride = "";
        bool openCLReTunePerBoardSize = false;
        enabled_t useFP16Mode = impl_->useFP16 ? enabled_t::True : enabled_t::False;
        enabled_t useNHWCMode = impl_->useNHWC ? enabled_t::True : enabled_t::False;
        std::string nnRandSeed = "weiqi";

        impl_->nnEval = std::make_unique<NNEvaluator>(
            config_.weightsPath,           // modelName
            config_.weightsPath,           // modelFileName
            expectedSha256,
            impl_->logger.get(),
            1,                              // maxBatchSize
            config_.boardSize,             // nnXLen
            config_.boardSize,             // nnYLen
            requireExactNNLen,
            inputsUseNHWC,
            nnCacheSizePowerOfTwo,
            nnMutexPoolSizePowerOfTwo,
            debugSkipNeuralNet,
            openCLTunerFile,
            homeDataDirOverride,
            openCLReTunePerBoardSize,
            useFP16Mode,
            useNHWCMode,
            config_.threads,               // numThreads
            gpuIdx,
            nnRandSeed,
            false,                          // doRandomize
            0                               // defaultSymmetry
        );

        impl_->nnModelName = impl_->nnEval->getModelName();
        LOGI("  model: %s", impl_->nnModelName.c_str());

        // 5. Search params
        impl_->searchParams = SearchParams();
        impl_->searchParams.maxVisits = config_.maxVisits;
        impl_->searchParams.numSearchThreads = config_.threads;
        impl_->searchParams.nnCacheSizePowerOfTwo = nnCacheSizePowerOfTwo;

        // 6. Time controls (unlimited, search until maxVisits)
        impl_->timeControls = TimeControls();

        // 7. AsyncBot (KataGo v1.16.4 constructor)
        impl_->bot = std::make_unique<AsyncBot>(
            impl_->searchParams,
            impl_->nnEval.get(),
            impl_->logger.get(),
            nnRandSeed
        );

        impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);

        ready_.store(true);
        LOGI("Builtin KataGo engine ready");
        return true;

    } catch (const std::exception& e) {
        LOGE("Exception: %s", e.what());
        return false;
    } catch (...) {
        LOGE("Unknown exception");
        return false;
    }
}

void BuiltinKataGoEngine::shutdown() {
    if (shutdown_.exchange(true)) return;
    stopAnalysis();
    if (analysisThread_.joinable()) analysisThread_.join();
    std::lock_guard<std::mutex> lock(commandMutex_);
    impl_->bot.reset();
    impl_->nnEval.reset();
    impl_->logger.reset();
    ready_.store(false);
    LOGI("Engine shut down");
}

std::string BuiltinKataGoEngine::sendCommand(
    const std::string& command, std::string& errorMessage) {
    std::lock_guard<std::mutex> lock(commandMutex_);
    if (!ready_.load()) { errorMessage = "Engine not started"; return ""; }
    try {
        return handleGtpCommand(command, errorMessage);
    } catch (const std::exception& e) {
        errorMessage = std::string("Exception: ") + e.what();
        return "";
    } catch (...) {
        errorMessage = "Unknown exception";
        return "";
    }
}

// ===== GTP 命令 =====
std::string BuiltinKataGoEngine::handleGtpCommand(
    const std::string& command, std::string& errorMessage) {
    std::istringstream iss(command);
    std::string cmd;
    iss >> cmd;
    std::transform(cmd.begin(), cmd.end(), cmd.begin(), ::tolower);

    if (cmd == "protocol_version") return "2";
    if (cmd == "name") return "KataGo";
    if (cmd == "version") return version_;

    if (cmd == "boardsize") {
        int size; iss >> size;
        if (size < 2 || size > 19) { errorMessage = "unacceptable size"; return ""; }
        impl_->boardSize = size;
        impl_->board = Board(size, size);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;
        if (impl_->bot) impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        return "";
    }
    if (cmd == "clear_board") {
        impl_->board = Board(impl_->boardSize, impl_->boardSize);
        impl_->hist = BoardHistory(impl_->board, C_BLACK, impl_->rules, 0);
        impl_->nextPla = C_BLACK;
        if (impl_->bot) impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        return "";
    }
    if (cmd == "komi") {
        double komi; iss >> komi;
        impl_->komi = komi;
        impl_->rules.komi = komi;
        if (impl_->bot) impl_->bot->setKomiIfNew((float)komi);
        return "";
    }
    if (cmd == "play") {
        std::string color, vertex;
        iss >> color >> vertex;
        Player pla = (color[0] == 'w' || color[0] == 'W') ? C_WHITE : C_BLACK;
        if (vertex == "pass" || vertex == "PASS") {
            // pass: record in history but no board change
            impl_->hist.makeBoardMoveAssumeLegal(impl_->board, Board::PASS_LOC, pla, nullptr);
            impl_->nextPla = getOpp(pla);
            if (impl_->bot) impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
            return "";
        }
        Loc loc = gtpToLoc(vertex, impl_->boardSize, impl_->boardSize);
        if (loc == Board::NULL_LOC) { errorMessage = "illegal move"; return ""; }
        impl_->hist.makeBoardMoveAssumeLegal(impl_->board, loc, pla, nullptr);
        impl_->nextPla = getOpp(pla);
        if (impl_->bot) impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        return "";
    }
    if (cmd == "genmove") {
        std::string color; iss >> color;
        Player pla = (color[0] == 'w' || color[0] == 'W') ? C_WHITE : C_BLACK;
        if (!impl_->bot) { errorMessage = "bot not initialized"; return ""; }
        // KataGo v1.16.4: genMoveSynchronous requires TimeControls
        Loc loc = impl_->bot->genMoveSynchronous(pla, impl_->timeControls);
        if (loc == Board::PASS_LOC) return "pass";
        if (loc == Board::NULL_LOC) return "pass";
        impl_->hist.makeBoardMoveAssumeLegal(impl_->board, loc, pla, nullptr);
        impl_->nextPla = getOpp(pla);
        impl_->bot->setPosition(impl_->nextPla, impl_->board, impl_->hist);
        return locToGtp(loc, impl_->boardSize, impl_->boardSize);
    }
    if (cmd == "kata-analyze" || cmd == "lz-analyze") {
        return runAnalysisOnce();
    }
    if (cmd == "quit") {
        shutdown_ = true; return "";
    }
    errorMessage = "unknown command: " + cmd;
    return "";
}

std::string BuiltinKataGoEngine::runAnalysisOnce() {
    if (!impl_->bot) return "";
    Loc loc = impl_->bot->genMoveSynchronous(impl_->nextPla, impl_->timeControls);
    std::ostringstream json;
    json << "{\"move\":\"" << locToGtp(loc, impl_->boardSize, impl_->boardSize)
         << "\",\"winrate\":0.5,\"visits\":" << config_.maxVisits << "}";
    return json.str();
}

// ===== 流式分析 =====
void BuiltinKataGoEngine::startAnalysis(
    const std::string& command, AnalysisCallback callback) {
    if (!ready_.load()) return;
    stopAnalysis();
    analysisCommand_ = command;
    analysisCallback_ = std::move(callback);
    analysisRunning_.store(true);
    analysisThread_ = std::thread(&BuiltinKataGoEngine::analysisLoop, this);
}

void BuiltinKataGoEngine::stopAnalysis() {
    if (!analysisRunning_.exchange(false)) return;
    if (impl_->bot) impl_->bot->stopWithoutWait();
    if (analysisThread_.joinable()) analysisThread_.join();
}

void BuiltinKataGoEngine::analysisLoop() {
    while (analysisRunning_.load() && !shutdown_.load()) {
        std::string result = runAnalysisOnce();
        if (!result.empty() && analysisCallback_) analysisCallback_(result);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
    }
    analysisRunning_.store(false);
}

std::string BuiltinKataGoEngine::sendCommandLocked(
    const std::string& command, std::string& errorMessage) {
    return handleGtpCommand(command, errorMessage);
}

} // namespace weiqi

#endif // WEIQI_BUILTIN_MODE
