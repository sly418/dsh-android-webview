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

## 许可

本项目原创部分（Android 壳、构建脚本、文档及本打包成品）采用 **PolyForm Noncommercial License 1.0.0** —— 可免费使用、修改、分发，但**仅限非商业目的**，分发须保留署名声明，二改版本同样不可商用。

打包进来的第三方组件（dsh、Node.js、Shizuku、bash 等）**不受本协议约束**，各自遵循其原始许可（详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）。尤其 dsh 为 DeepSeek 的 MIT 许可，任何人可直接从 DeepSeek 获取并按 MIT 使用；本协议无意也无权限制这一点。

- 英文法律正文：[`LICENSE`](LICENSE)
- 中文解读（非官方）：[`LICENSE.zh-CN.md`](LICENSE.zh-CN.md)
- 第三方组件声明：[`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- 非官方关系 / 角色 / 商标 / 责任声明：[`DISCLAIMER.md`](DISCLAIMER.md)

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

- **JDK 17+**
- **Android SDK**：compileSdk 37、build-tools 36.0.0、Android Gradle Plugin 9.5.2
- **Git LFS**（clone 时必须安装，否则大文件只有指针）

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
export JAVA_HOME=/path/to/jdk17      # Windows: set JAVA_HOME=...
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug              # Windows: gradlew.bat assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

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

## 已知限制

- 仅支持 `arm64-v8a`。
- Shizuku shell 模式下 dsh 的 landlock 沙箱后端不可用，会自动降级为无沙箱执行。
- Shizuku 需要用户自行在设备上授权（依赖 Shizuku 已有的权限模型）。
- **首次部署后正常；但退出应用再重新进入时，偶发"启动失败：process hasn't exited"**。
  表现为启动进度条走完后弹出错误面板，提示 `process hasn't exited`，并提供「重试」与「查看运行日志」按钮。
  这是 Shizuku 远程进程在重启时与旧进程状态检查之间的竞态导致的，并非 dsh 本体故障。
  **规避方法**：点击界面上的「重试」按钮，稍等片刻即可正常启动。若仍失败可再次重试。

  > 说明：该问题只在重新进入时出现，首次安装部署不受影响；不影响应用功能本身。

## 致谢

- [DeepSeek Harness](https://github.com/deepseek-ai) —— 核心运行时（MIT）
- [Shizuku](https://github.com/RikkaApps/Shizuku) —— shell 权限能力（Apache-2.0）
- [Node.js](https://nodejs.org) —— JavaScript 运行时（MIT）
- [Termux](https://termux.dev) —— bash 与依赖库
