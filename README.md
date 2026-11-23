# DshWebView Android Shell

一个把 [DeepSeek Harness (dsh)](https://github.com/deepseek-ai) 的 Web UI 与运行时打包进 Android 应用的壳，支持在 **Shizuku shell 权限**下以 `uid=2000(shell)` 运行，从而在手机上获得接近桌面 shell 的体验（含可用的 bash）。

> ⚠️ **重要声明**：本项目是**独立的非官方项目**，与 **腾讯、混元 AI、WorkBuddy、DeepSeek** 等各方**无隶属、授权或背书关系**。署名中的 "Tencent Hunyuan Hy" 仅用于标识生成代码的 AI 助手身份，不构成官方背书。商标、角色、责任等完整声明见 [`DISCLAIMER.md`](DISCLAIMER.md)。

## 功能

- **双模式运行**：
  - **Shizuku shell 模式**（推荐）：通过 Shizuku 的 `newProcess` 以 shell 权限运行 Node 与 bash，可访问普通 App 沙箱外的路径。
  - **普通模式**：未授权 Shizuku 时自动降级，以 App 自身权限运行。
- **完整内置运行时**：dsh Web 前端、Node.js、`@deepseek-ai/*` 全套包、bash 与依赖库全部打包进 APK，首次启动解压到共享目录。
- **网络适配**：合并 Android 系统 CA 证书到 `NODE_EXTRA_CA_CERTS`，解决 Node 不信任系统证书导致的 TLS 失败。
- **Android 兼容层**：`android-compat.cjs` shim 处理 Android SELinux 禁止硬链接等问题。
- **移动端适配**：安全区（状态栏/刘海）处理、触控目标与字号优化、响应式布局。

### 2.0.1 新增：设备能力工具层（MCP）

- **MCP 工具层**：通过 dsh 原生的 `streamable-http` MCP 接入（`--patch` 挂载），把 Android 能力注册成 dsh 可调用的工具，无需改动 dsh 核心。工具集完全自研：
  - `get_battery`（电量）、`vibrate`（震动）、`notify`（通知）、`get_clipboard`（剪贴板）
  - `capture_photo`（摄像头拍照 → 标准 ImageContent 交给模型）、`capture_screen`（截屏 → 模型）
  - `transcribe_audio`（语音转文字，实验性）
- **快捷工具面板**：界面右下角 ⚙ 按钮展开，一键调用上述设备能力，结果以提示或图片预览呈现。
- **科技风皮肤**：默认深色科技风，右上角 ✦ 按钮可在「科技风 / 原版」间一键切换并记住选择。皮肤通过覆盖 dsh 的设计令牌实现，**不改动 dsh 的 DOM 或原有功能**，随时可切回官方原版界面。
- **状态栏主题联动**：切换界面主题时原生状态栏图标颜色同步适配。

> 说明：语音的离线模型（内置 sherpa-onnx）仍在完善中，当前 `transcribe_audio` 为实验性功能。数据流向详见 [`PRIVACY.md`](PRIVACY.md)。

## 许可

本项目原创部分（Android 壳、构建脚本、文档及本打包成品）采用 **PolyForm Noncommercial License 1.0.0** —— 可免费使用、修改、分发，但**仅限非商业目的**，分发须保留署名声明，二改版本同样不可商用。

打包进来的第三方组件（dsh、Node.js、Shizuku、bash 等）**不受本协议约束**，各自遵循其原始许可（详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）。尤其 dsh 为 DeepSeek 的 MIT 许可，任何人可直接从 DeepSeek 获取并按 MIT 使用；本协议无意也无权限制这一点。

- 英文法律正文：[`LICENSE`](LICENSE)
- 中文解读（非官方）：[`LICENSE.zh-CN.md`](LICENSE.zh-CN.md)
- 第三方组件声明：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- 非官方关系 / 角色 / 商标 / 责任声明：[`DISCLAIMER.md`](DISCLAIMER.md)
- 隐私 / 遥测 / 数据流向说明：[`PRIVACY.md`](PRIVACY.md)

**共同作者 / 许可人**：WorkBuddy (Tencent Hunyuan Hy)、sly418（GitHub: <https://github.com/sly418>）

两位均为本项目作者。WorkBuddy 主要承担设计与实现，sly418 提供需求、真机环境、测试与构建配置。两位作者共同享有著作权并可依法维权。

> 关于 WorkBuddy 署名中 "Tencent Hunyuan Hy" 的含义（仅标识 AI 助手身份、非官方背书）及完整法律边界，见 [`DISCLAIMER.md` 第二节：角色声明](DISCLAIMER.md#二角色声明--role-and-authorship-disclosure)。

> 注意：禁止商用的协议不属于 OSI 定义的开源协议，GitHub 会显示为 "Other"。这是许可条款的必然结果。

## 上传到代码托管平台的注意事项

仓库内 `app/src/main/assets/runtime/dsh.zip` 约 **156 MB**，**超过 GitHub 单文件 100 MB 的硬限制**，直接 `git push` 会被拒绝。可选方案：

1. **Git LFS**（推荐）：安装 git-lfs 后 `git lfs install && git lfs track "*.zip" "*.so"`，再提交 `.gitattributes`。GitHub LFS 免费额度 10 GB，够用。
2. **运行时下载**：把 dsh.zip 放到 Releases 或对象存储，构建脚本里增加下载步骤（不在仓库内保留大文件）。
3. **换平台**：GitLab 单文件上限更高（默认 100 MB 可由管理员调整），Gitee 等也可考虑。

此外 `.gitignore` 默认忽略了 `local.properties`（含本机 SDK 路径）与 `build/` 产物；若希望提供免编译 APK，可单独在 GitHub Releases 上传 APK，或调整 `.gitignore`。

## 构建

**所有内置资源已包含在仓库中**（通过 Git LFS）：dsh.zip、shellruntime.zip、原生库 .so、Shizuku AAR 都已就位，**无需额外下载或准备脚本**。clone 后直接构建即可。

### 环境要求

- **JDK 25**（实际构建验证环境为 OpenJDK 25.0.4 / Android Studio 2026.2.2 自带 JBR）
  - 注意：源码 `sourceCompatibility` 为 Java 17，但 **Gradle 9.9.2 需要 JDK 17+ 且 AGP 9.5.2 实测在 JDK 25 下构建通过**；用 JDK 17 能否成功未经本项目验证，建议直接用 JDK 25 以复现本文结果。
- **Android SDK**：compileSdk 37、build-tools 36.0.0、Android Gradle Plugin 9.5.2（版本见 `build.gradle` 与 `settings.gradle`）
- **Git LFS**（clone 时必须安装，否则大文件只有指针）

### 依赖版本与可复现性

构建工具链版本已固定：Gradle **9.9.2**（`gradle/wrapper/gradle-wrapper.properties`）、AGP **9.5.2**（根 `build.gradle`）、Shizuku API/provider **13.1.5**（本地 AAR，位于 `app/libs/`，**不依赖网络下载**）。第三方运行时（dsh、Node.js、npm 依赖树）以 **dsh.zip 形式随仓库固定打包**，版本不随构建漂移。

因此本项目的**代码与运行时资源是可复现的**（同一 commit 拿到的是同一份 dsh.zip）。需注意的边界：Gradle/AGP 从 `google()` / `mavenCentral()` 在线解析，若这些仓库未来下线对应版本则无法构建；此类外部依赖不在本项目控制范围内。

### 方式一：local.properties（推荐）

在项目根目录创建 `local.properties`（该文件已被 gitignore 排除，不会上传），内容：

```properties
sdk.dir=/你的/Android/Sdk 路径
```

Windows 示例：`sdk.dir=C\:\\Users\\你的用户名\\AppData\\Local\\Android\\Sdk`（注意反斜杠转义）。
然后用 JDK 17+ 运行：

```bash
./gradlew assembleDebug
```

### 方式二：环境变量

不创建 local.properties 也可以，直接设置环境变量：

```bash
export JAVA_HOME=/path/to/jdk25      # Windows: set JAVA_HOME=...；本文验证环境为 JDK 25
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug              # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 构建校验流程

构建完成后，用随仓库提供的脚本校验 APK 的完整性、关键资源与 SHA-256（仅需 Python 3，无第三方依赖）：

```bash
python tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
# 与已知构建的 SHA-256 比对：
python tools/verify_apk.py app/build/outputs/apk/debug/app-debug.apk --sha256 <期望的SHA256>
```

脚本检查：ZIP 完整性、AndroidManifest / classes*.dex / dsh.zip / shellruntime.zip 是否齐全、dsh.zip 是否为真实的 Git LFS 内容（而非未拉取的指针文件），并输出 SHA-256。全部通过时打印 `RESULT: PASS`，退出码 0。

可选：用 Android SDK 的 `aapt dump badging <apk>` 核对 `versionName` / `versionCode` / 包名，用 `apksigner verify --print-certs <apk>` 核对签名（这两个工具需要 JDK 与 SDK build-tools）。

## 使用

1. 安装 APK（MIUI 等系统可能要求在文件管理器中手动安装）。
2. 若手机已安装 [Shizuku](https://github.com/RikkaApps/Shizuku)，首次启动会请求授权；同意后应用以 shell 权限重启。
3. 未授权则自动使用普通模式。
4. 打开应用内的设置界面配置 DeepSeek API Key 等。

## 目录结构

```
app/src/main/
  java/com/deepseek/dshwebview/   # Java 源码（Activity、Service、启动器、安装器）
  java/moe/shizuku/server/        # 编译后的 Shizuku AIDL 接口
  aidl/                           # （历史）AIDL 源文件，已改为预编译 Java
  assets/runtime/dsh.zip          # dsh 运行时与 Web 前端
  assets/shellruntime.zip         # bash 与依赖库
  libs/*.aar                      # Shizuku API / Provider
  jniLibs/arm64-v8a/              # libnodeexec.so
```

## 健壮性改进

- **进程管理**：重启时安全回收旧进程并等待其退出；用启动代次（generation）丢弃过期启动结果，避免竞态；原子启动锁防止重复拉起。
- **后台运行**：前台服务 + `START_STICKY`；启动阶段持有超时 WakeLock（5 分钟上限，结束即释放）；服务被系统回收后自动重建。注：锁屏/切后台的实际存活时间受各厂商 ROM 省电策略影响。
- **异常恢复**：保存最近 URL、启动模式与启动状态；进程重建后自动重启运行时并提示用户。仅保存非敏感启动元数据，不含 API Key 或会话内容。
- **Shizuku 权限**：监听 binder 死亡与重新授权，服务重启后自动切回 shell 模式；授权撤销时降级普通模式。
- **环境自检**：启动前检查 Node 可执行文件、兼容层、动态库、工作目录与 CA 证书，缺失时给出精确错误而非笼统的"进程退出"。
- **日志分类**：应用自身事件按四类分别记录到 `filesDir/logs/` 下：`app-startup.log`（启动）、`app-permission.log`（Shizuku 权限）、`app-runtime.log`（运行）、`app-error.log`（异常）；dsh 运行时自身的 stdout/stderr 仍在 `dsh.log` / `dsh-shell.log`。应用内「查看运行日志」显示内存中的最近日志。
- **资源占用采样**：内置低频采样器，每 30 秒把内存（PSS/RSS）、CPU 占用率、设备温度、可用内存写入 `filesDir/logs/app-resources.log`（带时间戳）。可用于对比空闲、连续对话、文件处理等场景下的资源表现。采样为只读、独立线程，不影响运行时。
  - 查看：`adb shell "run-as com.deepseek.dshwebview cat files/logs/app-resources.log"`（debug 包可调 `run-as`），或在文件管理器中查看应用私有目录（需 root）。

## 已知限制

- 仅支持 `arm64-v8a`。
- Shizuku shell 模式下 dsh 的 landlock 沙箱后端不可用，会自动降级为无沙箱执行。
- Shizuku 需要用户自行在设备上授权（依赖 Shizuku 已有的权限模型）。
- **重新进入应用时的进程竞态（已修复并实测验证）**：旧版本偶发"启动失败：process hasn't exited / Node 进程已退出，退出码=-1"。
  根因有二：① Shizuku 以 shell 身份（uid 2000）运行的 Node 不属于 app 进程组，app 被 force-stop 后它仍残留约 2 秒并占用 3080 端口；
  ② `ShizukuRemoteProcess.isAlive()` 在新进程刚启动时可能瞬时误报。当前版本已引入：启动脚本先清理残留 Node 并等端口释放、
  启动宽限期（8 秒内不误判失败）、一次自动重试、安全进程回收、启动代次（generation）机制、原子启动锁。
  已在真机连续多轮快速重进验证：逐秒截屏 25 张无一出现错误面板，另 3 轮零错误。关键设计：不再用不可靠的 `isAlive()` 触发错误，
  只以"服务实际响应且捕获到 tokenized URL"为就绪信号；极端超时才静默自愈一次，全程不闪错误面板。如仍偶遇，点击「重试」即可。

  > 说明：该问题只在重新进入时出现，首次安装部署不受影响；不影响应用功能本身。

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai) —— 核心运行时（MIT）
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— shell 权限能力（Apache-2.0）
- [Node.js](https://nodejs.org) —— JavaScript 运行时（MIT）
- [Termux](https://termux.dev) —— bash 与依赖库
