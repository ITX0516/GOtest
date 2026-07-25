# 阿Q围棋（WeiqiApp）集成说明

本项目对标阿Q围棋，使用 Android Kotlin + Jetpack Compose 实现，内置 KataGo / LeelaZero 围棋引擎并支持远程算力。

代码框架已就绪，但**真实引擎二进制与网络权重文件因体积/版权原因未打包**。首次构建前请按本文档完成集成。

---

## 1. 目录结构概览

```
weiqi-app/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                          # JNI 桥接 + GTP 引擎封装
│       │   ├── CMakeLists.txt
│       │   ├── jni_bridge.cpp
│       │   ├── gtp_engine.{h,cpp}
│       │   ├── builtin_katago_engine.{h,cpp}   # BUILTIN 模式
│       │   ├── process_engine.{h,cpp}          # PROCESS 模式
│       │   ├── real_engines.{h,cpp}            # PROCESS 模式
│       │   ├── dylib_engine.{h,cpp}            # DYLIB 模式
│       │   └── third_party/katago/             # ← KataGo 源码（BUILTIN 模式）
│       ├── java/com/weiqi/app/
│       │   ├── core/                     # 围棋核心逻辑（Board/Rules/GameState）
│       │   ├── engine/                   # 引擎接口、JNI 桥、EngineManager
│       │   │   └── jni/NativeEngineBridge.kt
│       │   ├── sgf/                      # SGF 解析与导出
│       │   ├── remote/                   # 远程计算（智星云/算云/个人PC）
│       │   ├── ui/
│       │   │   ├── board/                # 棋盘 Compose 绘制
│       │   │   ├── theme/                # 主题/音效
│       │   │   ├── play/                 # 对弈模式
│       │   │   ├── analysis/             # 分析模式
│       │   │   ├── settings/             # 设置页
│       │   │   ├── NavGraph.kt
│       │   │   └── HomeScreen.kt
│       │   ├── MainActivity.kt
│       │   └── WeiqiApp.kt
│       ├── jniLibs/<abi>/                # ← DYLIB 模式用：引擎 .so
│       ├── assets/
│       │   ├── weights/                  # ← 权重文件（见 §3）
│       │   └── bin/<abi>/                # ← PROCESS 模式用：引擎可执行文件
│       └── res/
│           ├── raw/                      # 落子音效占位 XML（建议替换为 wav）
│           ├── drawable/                 # 启动图标
│           └── values/                   # 字符串/颜色/主题
├── tools/
│   ├── download_katago_source.sh        # 下载 KataGo 源码
│   ├── download_weights.sh              # 下载权重文件
│   ├── build_katago_android.sh          # 独立编译 KataGo（PROCESS 模式用）
│   └── build_leelazero_android.sh       # LeelaZero 编译指引
└── build.gradle.kts
```

---

## 2. 四种引擎集成模式

本项目支持四种引擎集成方式，通过 `app/build.gradle.kts` 中的 `WEIQI_ENGINE_MODE` 切换：

| 模式 | 说明 | 推荐度 | 性能 | 集成难度 |
|---|---|---|---|---|
| **BUILTIN** | KataGo 源码直接编译进 `libweiqi_engine.so` | ⭐⭐⭐⭐⭐ | 最高 | 中 |
| **PROCESS** | 子进程启动 `katago` 可执行文件，GTP pipe 通信 | ⭐⭐⭐⭐ | 高 | 低 |
| **DYLIB** | `dlopen` 加载 `libkatago.so` | ⭐⭐⭐ | 高 | 高 |
| **STUB** | 桩实现，随机落子，UI 调试用 | - | - | - |

> 默认模式为 `STUB`。正式使用请切换到 `BUILTIN` 或 `PROCESS`。

### 2.1 模式切换

编辑 `app/build.gradle.kts`，在 `externalNativeBuild.cmake.arguments` 中设置：

```kotlin
externalNativeBuild {
    cmake {
        // 四选一：BUILTIN / PROCESS / DYLIB / STUB
        arguments += listOf("-DWEIQI_ENGINE_MODE=BUILTIN")
    }
}
```

---

## 3. 模式一：BUILTIN（推荐⭐）

KataGo 源码直接编译进 APK 的 `libweiqi_engine.so`，无需额外可执行文件或 .so。集成度最高，性能最好。

### 3.1 下载 KataGo 源码

```bash
cd weiqi-app
./tools/download_katago_source.sh
```

脚本会自动克隆 KataGo v1.16.4 到 `app/src/main/cpp/third_party/katago/`。

### 3.2 下载权重

```bash
./tools/download_weights.sh --size b18
```

可选大小：
- `b10` — ~12MB，快速，业余 1-3 段
- `b18` — ~93MB，平衡，业余 5-6 段（推荐）
- `b28` — ~259MB，最强，职业水平

### 3.3 构建

```bash
./gradlew :app:assembleDebug
```

编译时 CMake 会自动收集 KataGo 源文件并一起编译进 `libweiqi_engine.so`。

### 3.4 注意事项

- 首次编译耗时较长（KataGo 源码约 200+ 个 .cpp 文件）
- 仅支持 KataGo（LeelaZero 无 BUILTIN 模式）
- 默认 CPU-only 模式（`USE_CPU_ONLY=1`），兼容性最好
- 权重文件需放在 `app/src/main/assets/weights/` 下

---

## 4. 模式二：PROCESS（子进程方式）

通过 `fork+exec` 启动引擎可执行文件，stdin/stdout 收发 GTP 命令。不需要修改引擎源码，兼容性最好。

### 4.1 编译引擎可执行文件

#### KataGo

```bash
cd weiqi-app
./tools/build_katago_android.sh --abi arm64-v8a --cpu-only
```

脚本会自动：
1. 克隆 KataGo 源码
2. 用 NDK 交叉编译
3. 询问是否复制到 `app/src/main/assets/bin/arm64-v8a/`

#### LeelaZero

LeelaZero 官方 Android 支持较弱，编译依赖较多。参考 `tools/build_leelazero_android.sh` 中的指引。

### 4.2 手动放置（已有二进制时）

如果你从其他渠道获得了 `katago` 可执行文件：

```
app/src/main/assets/bin/
├── arm64-v8a/
│   └── katago            # 可执行文件，注意 chmod +x
└── x86_64/               # 模拟器用
    └── katago
```

### 4.3 下载权重

同 BUILTIN 模式：

```bash
./tools/download_weights.sh --size b18
```

### 4.4 构建

在 `app/build.gradle.kts` 中设置 `WEIQI_ENGINE_MODE=PROCESS`，然后正常构建。

App 启动引擎时，`EngineManager.ensureBinaryExtracted()` 会自动把 `katago` 从 assets 解压到 `filesDir/bin/` 并设置可执行权限。

---

## 5. 模式三：DYLIB（动态库 dlopen）

通过 `dlopen` 动态加载 `libkatago.so` / `libleelaz.so`，调用约定的 C ABI。性能好但需要引擎导出 C 接口。

### 5.1 C 接口约定

引擎 .so 必须导出以下函数（详见 `kata_bridge.h` / `leelaz_bridge.h`）：

```c
void* katago_create(const char* config_json, char* error_msg, int error_msg_len);
void  katago_destroy(void* handle);
char* katago_send_command(void* handle, const char* command, char* error_msg, int error_msg_len);
void  katago_free_string(char* str);
bool  katago_start_analysis(void* handle, const char* command, KatagoAnalysisCallback callback, void* user_data);
void  katago_stop_analysis(void* handle);
bool  katago_is_ready(void* handle);
const char* katago_name(void* handle);
const char* katago_version(void* handle);
```

KataGo / LeelaZero 官方源码不直接提供这些接口，需要自己写一层包装。

### 5.2 放置 .so

```
app/src/main/jniLibs/
├── arm64-v8a/
│   ├── libkatago.so
│   └── libleelaz.so
└── x86_64/
    └── libkatago.so
```

### 5.3 构建

设置 `WEIQI_ENGINE_MODE=DYLIB`，正常构建即可。

---

## 6. 放置网络权重

无论哪种模式都需要权重文件。

### 6.1 一键下载

```bash
./tools/download_weights.sh --size b18   # 推荐
# 或全部下载
./tools/download_weights.sh --all
```

### 6.2 手动放置

```
app/src/main/assets/weights/
├── katago_b18c384nbt.bin.gz
└── leelaz_b18c384.txt.gz
```

文件名对应 `EnginePreferences.kt` 中的常量：
- `ASSET_KATAGO_WEIGHTS = "weights/katago_b18c384nbt.bin.gz"`
- `ASSET_LEELA_WEIGHTS = "weights/leelaz_b18c384.txt.gz"`

App 首次启动引擎时，`EngineManager.ensureWeightsExtracted()` 会自动把 assets 解压到 `filesDir/weights/`。

### 6.3 棋力参考

| 设备 | 引擎 | visits | 大致棋力 |
|---|---|---|---|
| 骁龙 8 Gen2 / 旗舰 | KataGo b18 + GPU | 800 | 业余 5-6 段 |
| 中端机 | KataGo b18 CPU | 300 | 业余 3 段 |
| 入门机 | KataGo b10 CPU | 100 | 业余 1 级 |
| 任意机 | 远程算力（RTX 3090） | 5000 | 职业水平 |

UI 中 `AiStrength` 枚举已对应不同 visits 档位。

---

## 7. 远程算力配置

App 设置页 → 引擎 → 选择 "远程计算" 后填入：

| 字段 | 说明 |
|---|---|
| 主机/IP | 远程 GTP 服务地址 |
| 端口 | 默认 8080 |
| 平台标识 | `zhixing` / `suanyun` / `custom` / `ssh` |
| 密码 | 可选，部分 GTP 服务端需 `auth` 命令 |
| 最大访问数 | 远端引擎 visits 上限 |

### 7.1 个人 PC 接入

在 PC 上以 GTP-over-TCP 模式启动引擎：

```bash
# KataGo
katago gtp -model b18c384.bin.gz -config default.gtp.cfg
# 通过 socat 把 stdin/stdout 暴露为 TCP 端口
socat TCP-LISTEN:8080,fork EXEC:"katago gtp -model b18c384.bin.gz"
```

App 中平台标识填 `custom`，主机填 PC 局域网 IP。

### 7.2 智星云 / 算云

平台 API 实际字段需根据官方文档对齐，已在代码中标注 `// TODO: 实际接入时根据官方文档调整`。

集成步骤：
1. 注册账号获取 API token；
2. 在云平台租用 GPU 实例（推荐 RTX 3090 / A100），预装 KataGo；
3. 实例进入 RUNNING 后，平台返回 GTP 接入点（host:port）；
4. App 中填入 token，调用 `ZhixingCloudClient` / `SuanyunClient` 的 `awaitRunning` + `getGtpEndpoint`。

参考代码：[ZhixingCloudClient.kt](file:///workspace/weiqi-app/app/src/main/java/com/weiqi/app/remote/ZhixingCloudClient.kt)、[SuanyunClient.kt](file:///workspace/weiqi-app/app/src/main/java/com/weiqi/app/remote/SuanyunClient.kt)。

### 7.3 SSH 隧道

PC 在外网时，先用 `ssh -L 8080:localhost:8080 user@pc-ip` 建立隧道，App 中平台标识填 `ssh`、主机填 `127.0.0.1`、端口填本地隧道端口。

---

## 8. 落子音效

`app/src/main/res/raw/` 下为占位 XML（无法播放）。替换为真实音频：

```
raw/
├── stone_black.wav    # 黑子落子声
├── stone_white.wav    # 白子落子声（可与 black 相同）
├── capture.wav        # 提子声
└── pass.wav           # 弃权声
```

`SoundManager` 加载失败时静默跳过，不会崩溃。

---

## 9. 构建与运行

```bash
cd weiqi-app
./gradlew :app:assembleDebug
```

环境要求：
- Android Studio Hedgehog 以上
- JDK 17
- Android SDK 34（compileSdk）
- NDK 26+、CMake 3.22+
- minSdk 24（Android 7.0）

若未集成真实引擎，App 仍可构建运行，对弈时 AI 走子为随机合法点（STUB 模式），用于 UI 调试。

---

## 10. 快速开始（5 分钟跑通真实引擎）

```bash
# 1. 下载权重
./tools/download_weights.sh --size b18

# 2. 下载 KataGo 源码
./tools/download_katago_source.sh

# 3. 编辑 app/build.gradle.kts，设置 BUILTIN 模式
#    arguments += listOf("-DWEIQI_ENGINE_MODE=BUILTIN")

# 4. 构建并安装
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 11. 已知限制 / TODO

- **死活判定**：`GoRules.findDeadStones` 为启发式，复杂局面可能误判。终局建议通过引擎 `final_status_list dead` 命令确认（待在 `KataGoEngine` 中补全）。
- **BUILTIN 模式流式分析**：当前 `BuiltinKataGoEngine` 的分析为同步调用，后续可接入 KataGo 原生回调实现真正的流式输出。
- **智星云/算云 API**：路径与字段为推断，需对齐官方文档。
- **9 路/13 路棋盘**：核心逻辑已支持，但 `BoardView` 在小棋盘上的星位/坐标显示需进一步验证。
- **变着编辑**：当前 SGF 解析支持读取变着，但 UI 仅展示主线。变着编辑能力待后续实现。

---

## 12. GitHub Actions 云端编译（无需电脑）

如果没有本地开发环境，可以通过 GitHub Actions 免费云端编译 APK。

### 12.1 工作流一览

| 工作流文件 | 用途 | 触发方式 |
|---|---|---|
| `.github/workflows/build-apk.yml` | 构建完整 APK（含引擎 + 权重） | push / PR / 手动触发 |
| `.github/workflows/build-katago.yml` | 独立编译 KataGo 可执行文件 | 手动触发 |
| `.github/workflows/download-weights.yml` | 下载权重文件 | 手动触发 |
| `.github/workflows/release.yml` | 打 tag 自动发版到 GitHub Releases | 推送 `v*` tag / 手动触发 |

### 12.2 快速构建 APK（3 步）

**第 1 步**：把代码推到 GitHub 仓库

**第 2 步**：在仓库页面点击 **Actions** → 选择 **Build Android APK** → 点击 **Run workflow**

参数说明：
- `engine_mode`：选 `BUILTIN`（KataGo 源码内置，推荐）
- `weight_size`：选 `b18`（约 93MB，业余 5-6 段）
- `build_type`：选 `release`（正式版）或 `debug`（调试版）

**第 3 步**：等待编译完成（约 30-60 分钟），在 **Summary** 页面底部下载 APK 安装包

### 12.3 发布正式版

推送一个 tag 即可自动构建并发布到 GitHub Releases：

```bash
git tag v1.0.0
git push origin v1.0.0
```

`release.yml` 会自动：
1. 下载 KataGo 源码 + b18 权重
2. BUILTIN 模式编译 APK
3. 自动签名（需配置 Secrets）
4. 创建 GitHub Release 并上传 APK

### 12.4 配置签名（可选）

release 构建默认会尝试签名。要启用自动签名，在 GitHub 仓库 **Settings → Secrets → Actions** 中添加以下 Secrets：

| Secret 名称 | 说明 |
|---|---|
| `ANDROID_SIGNING_KEY` | keystore 文件的 base64 编码 |
| `ANDROID_KEY_ALIAS` | key 别名 |
| `ANDROID_KEY_STORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_PASSWORD` | key 密码（通常和 keystore 密码相同） |

生成 base64：
```bash
base64 -i your-keystore.jks -o keystore-base64.txt
```

未配置 Secrets 时，release 构建仍会成功（跳过签名步骤），只是 APK 未签名。

### 12.5 注意事项

- GitHub Actions 免费账户每月有 2000 分钟额度（公开仓库无限）
- BUILTIN 模式首次编译较慢（KataGo 200+ 个 cpp 文件），约 20-40 分钟
- APK 因为包含权重文件会比较大（约 100MB+）
- 只编译 `arm64-v8a` 可减少约 2/3 编译时间
