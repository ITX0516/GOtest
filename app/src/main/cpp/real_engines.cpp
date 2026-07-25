#include "real_engines.h"

#include <sstream>

namespace weiqi {

// ============================================================================
// KataGoRealEngine
// ============================================================================

KataGoRealEngine::KataGoRealEngine(const std::string& weightsPath,
                                   const std::string& configPath,
                                   int threads,
                                   bool cpuOnly,
                                   double komi,
                                   int boardSize,
                                   const std::string& workingDir,
                                   const std::string& executablePath)
    : ProcessGtpEngine(
          /*executablePath=*/executablePath.empty()
              ? (workingDir.empty() ? std::string("katago") : workingDir + "/katago")
              : executablePath,
          buildArgs(weightsPath, configPath, threads, cpuOnly, komi, boardSize),
          workingDir) {
    setName("KataGo");
}

std::vector<std::string> KataGoRealEngine::buildArgs(
    const std::string& weightsPath,
    const std::string& configPath,
    int threads,
    bool cpuOnly,
    double komi,
    int boardSize) {
    std::vector<std::string> args;
    args.push_back("gtp");
    args.push_back("-model");
    args.push_back(weightsPath);

    if (!configPath.empty()) {
        args.push_back("-config");
        args.push_back(configPath);
    }

    // 线程数
    args.push_back("-override-config");
    args.push_back("numSearchThreads=" + std::to_string(threads));

    // 默认贴目
    args.push_back("-override-config");
    std::ostringstream komiStr;
    komiStr.precision(1);
    komiStr << std::fixed << komi;
    args.push_back("defaultKomi=" + komiStr.str());

    // 棋盘尺寸
    args.push_back("-override-config");
    args.push_back("maxBoardSize=" + std::to_string(boardSize));

    // CPU 模式（禁用 OpenCL）
    if (cpuOnly) {
        args.push_back("-override-config");
        args.push_back("nnMaxBatchSize=1");
        args.push_back("-override-config");
        args.push_back("openclReuseTuning=false");
        // 注意：KataGo CPU 模式实际通过 eigen 后端，
        // 可执行文件本身需编译时指定 -DUSE_CPU_ONLY=1
    }

    return args;
}

std::vector<std::string> KataGoRealEngine::finalStatusList(
    const std::string& status,
    std::string& errorMessage) {
    std::vector<std::string> result;
    std::string cmd = "final_status_list " + status;
    std::string resp = sendCommand(cmd, errorMessage);
    if (!errorMessage.empty()) return result;

    // 响应是多行，每行一个 GTP 坐标
    std::istringstream iss(resp);
    std::string line;
    while (std::getline(iss, line)) {
        if (!line.empty()) {
            result.push_back(line);
        }
    }
    return result;
}

// ============================================================================
// LeelazRealEngine
// ============================================================================

LeelazRealEngine::LeelazRealEngine(const std::string& weightsPath,
                                   int threads,
                                   bool cpuOnly,
                                   double komi,
                                   int boardSize,
                                   const std::string& workingDir,
                                   const std::string& executablePath)
    : ProcessGtpEngine(
          /*executablePath=*/executablePath.empty()
              ? (workingDir.empty() ? std::string("leelaz") : workingDir + "/leelaz")
              : executablePath,
          buildArgs(weightsPath, threads, cpuOnly),
          workingDir) {
    setName("LeelaZero");
    (void)komi;
    (void)boardSize;
}

std::vector<std::string> LeelazRealEngine::buildArgs(
    const std::string& weightsPath,
    int threads,
    bool cpuOnly) {
    std::vector<std::string> args;
    args.push_back("-w");
    args.push_back(weightsPath);
    args.push_back("-t");
    args.push_back(std::to_string(threads));
    args.push_back("--noponder");
    args.push_back("-g");  // GTP 模式

    // LeelaZero CPU 模式：可执行文件需编译时不带 OpenCL
    if (cpuOnly) {
        args.push_back("--cpu-only");  // 部分版本支持
    }

    return args;
}

} // namespace weiqi
