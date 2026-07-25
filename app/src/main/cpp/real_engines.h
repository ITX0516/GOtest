#ifndef WEIQI_REAL_ENGINES_H
#define WEIQI_REAL_ENGINES_H

#include <string>
#include <vector>

#include "gtp_engine.h"
#include "process_engine.h"

namespace weiqi {

/**
 * KataGo 真实引擎：通过子进程启动 katago 可执行文件。
 *
 * 命令行约定：
 *   katago gtp -model <weights> [-config <config>] [-override-config key=val]
 *
 * 权重文件与配置文件由调用方提供绝对路径（EngineManager 从 assets 解压到 filesDir）。
 *
 * 支持的扩展命令：
 * - kata-analyze <color> interval <ms> visits <n> json 1   — 流式 JSON 分析
 * - kata-genmove_analyze                               — 走子+分析合一
 * - final_status_list dead/alive/seki                   — 终局死活判定
 * - time_left ...                                       — 读秒控制
 */
class KataGoRealEngine : public ProcessGtpEngine {
public:
    /**
     * @param weightsPath     权重文件绝对路径（.bin.gz）
     * @param configPath      配置文件绝对路径（.cfg），可为空字符串
     * @param threads         搜索线程数
     * @param cpuOnly         仅 CPU 模式（不使用 GPU/OpenCL）
     * @param komi            默认贴目
     * @param boardSize       默认棋盘大小
     * @param workingDir      工作目录
     * @param executablePath  可执行文件绝对路径；为空则用 workingDir/katago 推断
     */
    KataGoRealEngine(const std::string& weightsPath,
                     const std::string& configPath,
                     int threads = 2,
                     bool cpuOnly = true,
                     double komi = 7.5,
                     int boardSize = 19,
                     const std::string& workingDir = "",
                     const std::string& executablePath = "");

    ~KataGoRealEngine() override = default;

    // 终局死活判定（KataGo 特有）
    // 返回被判定为死/活/公气的点列表，格式与 GTP 一致
    std::vector<std::string> finalStatusList(const std::string& status,
                                             std::string& errorMessage);

private:
    // 根据参数构造 katago gtp 启动参数
    static std::vector<std::string> buildArgs(const std::string& weightsPath,
                                               const std::string& configPath,
                                               int threads,
                                               bool cpuOnly,
                                               double komi,
                                               int boardSize);
};

/**
 * LeelaZero 真实引擎：通过子进程启动 leelaz 可执行文件。
 *
 * 命令行约定：
 *   leelaz -w <weights> [-t <threads>] [--noponder] [-g]
 *
 * 支持的扩展命令：
 * - lz-analyze <color> interval <ms> visits <n>   — 流式文本分析
 * - lz-hint <color>                                — 候选手建议
 */
class LeelazRealEngine : public ProcessGtpEngine {
public:
    /**
     * @param weightsPath     权重文件绝对路径
     * @param threads         搜索线程数
     * @param cpuOnly         仅 CPU 模式
     * @param komi            默认贴目
     * @param boardSize       默认棋盘大小
     * @param workingDir      工作目录
     * @param executablePath  可执行文件绝对路径；为空则用 workingDir/leelaz 推断
     */
    LeelazRealEngine(const std::string& weightsPath,
                     int threads = 2,
                     bool cpuOnly = true,
                     double komi = 7.5,
                     int boardSize = 19,
                     const std::string& workingDir = "",
                     const std::string& executablePath = "");

    ~LeelazRealEngine() override = default;

private:
    static std::vector<std::string> buildArgs(const std::string& weightsPath,
                                               int threads,
                                               bool cpuOnly);
};

} // namespace weiqi

#endif // WEIQI_REAL_ENGINES_H
