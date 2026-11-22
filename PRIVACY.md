# 隐私、遥测与数据流向说明 / Privacy, Telemetry & Data Flow

> 本文件如实记录本项目（含其打包的 dsh 运行时）的网络端点与数据处理行为，供用户审阅。**本项目作者未编写任何遥测或后门代码**；下文区分"本项目壳代码"与"打包的 dsh 组件"。详见 [`DISCLAIMER.md`](DISCLAIMER.md) 的非官方关系声明。

## 一句话结论

- 本项目壳代码**只与本机通信**（`127.0.0.1`），无隐藏外发。
- dsh 组件**内置了 DeepSeek 官方的产品遥测模块，但仅在桌面版启用**；本项目使用的 Web 前端不挂载该模块，因此**不会上报遥测**。
- dsh 会连接 **DeepSeek 官方 API**（登录、模型调用），这是其核心功能所需，未登录/未配置 API Key 前不会发生模型调用。

## 责任撇清声明 / Disclaimer of Responsibility

**中文**

- 人类共同作者 **sly418** 在本项目中仅承担**技术集成与封装**工作：编写 Android 壳、配置构建、适配移动端、提供真机测试环境。**sly418 未编写、未启用、未修改、未配置任何遥测、统计或数据上报代码**。
- 第 2 节所述的遥测模块及其默认行为，系 **DeepSeek 官方 MIT 组件（`@deepseek-ai/*`）自身所包含**的设计，其源代码、默认配置与数据处理逻辑均由 DeepSeek 定义，与本项目的集成作者无关。
- 在此基础上遥测在 Android 上不激活（见 2.1）。**任何有关 dsh 组件数据收集行为的询问、投诉或合规要求，应向相应组件的权利人（DeepSeek 等）提出**，而非本项目的集成作者。
- 本项目作者不对第三方组件的数据处理行为作任何承诺或担保，亦不因其使用而产生的任何后果承担责任。详见 [`DISCLAIMER.md`](DISCLAIMER.md) 第四节「责任与许可切割」。

**English**

- The human co-author **sly418** contributed only **technical integration and packaging**: the Android shell, build configuration, mobile adaptation, and device testing. **sly418 did not write, enable, modify, or configure any telemetry, analytics, or data-reporting code.**
- The telemetry modules and their default behavior described in Section 2 are part of the **official DeepSeek MIT components (`@deepseek-ai/*`) themselves**. Their source, default configuration, and data-handling logic are defined by DeepSeek, not by this project's integrator.
- As noted in 2.1, telemetry is not active on Android. **Any inquiries, complaints, or compliance requests regarding dsh components' data-collection behavior should be directed to the respective rights holders (e.g. DeepSeek), not to this project's integrator.**
- The authors make no representation or warranty regarding third-party components' data handling and accept no responsibility for consequences arising from their use. See Section 4 of [`DISCLAIMER.md`](DISCLAIMER.md), "Limitation of Liability and Severability".

---

## 1. 本项目壳代码（Java 层）

| 项目 | 内容 |
|---|---|
| 网络端点 | 仅 `http://127.0.0.1:3080/`（本地回环地址） |
| 用途 | App 内 WebView 与本地 dsh 服务之间的通信 |
| 外部连接 | 无 |
| 权限 | `INTERNET`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_SPECIAL_USE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`（均为功能必需） |
| 未申请 | 无短信、通讯录、位置、通话记录、文件全盘访问等敏感权限 |

源码位置：`app/src/main/java/com/deepseek/dshwebview/DshService.java`（`BASE_URL = "http://127.0.0.1:3080/"`）。

## 2. 打包的 dsh 运行时（`@deepseek-ai/*`, MIT）

### 2.1 产品遥测模块（存在，但 Android 上不激活）

dsh 依赖树中包含以下遥测相关模块（均为 DeepSeek 官方 MIT 组件）：

- `dsh-host-product-telemetry-otel`、`dsh-otel`、`dsh-client-product-analytics`
- OpenTelemetry / OTLP exporter 系列

其源码中定义的默认配置：

| 项 | 值 |
|---|---|
| 上报端点 | `https://dsh-otel-collector.deepseeksvc.com/v1/logs` |
| 路由头 | `x-channel: dsh_otel_report` |
| 默认开关 | `enabled: true`（但见下方说明） |
| 报告字段 | 设备 ID、用户 ID、OS 名称/版本、CPU 型号与核数、内存总量、app 版本、会话 ID、上下文压缩触发类型等；源码注明**不包含消息内容与凭证** |

**关键事实 — 为什么本项目不触发它：**

1. 源码注释明确标注该服务为 **"Desktop-only analytics"**（仅限桌面版），并写明 "ordinary Web does not mount this service"（普通 Web 不挂载该服务）。
2. 本项目的静态审查：dsh Web 前端的 **61 个 JS bundle 中，零引用** `productAnalytics` / `productTelemetry` / `dsh-otel-collector` / `deepseeksvc.com`。
3. 该模块依赖桌面端 IPC（Typert remote service）与桌面环境查询（Windows PowerShell / macOS），Android 环境不具备。

结论：**本 Android 应用不会向遥测端点上报数据。** 若未来 dsh 版本改变此行为，以届时源码为准。

### 2.2 DeepSeek 官方 API 端点（功能所需）

dsh 运行时中硬编码了以下官方域名：

| 域名 | 用途 |
|---|---|
| `https://api.deepseek.com` | 模型推理 API（需用户提供 API Key 或登录） |
| `https://harness.deepseek.com` | 账号登录相关 |
| `https://platform.deepseek.com` | 账号 / 平台相关 |
| `https://api.deepseek.com/v1/tools` | 工具调用 |

这些是 dsh 核心功能所必需。未登录且未配置 API Key 时，不会发生模型调用。

### 2.3 主动反馈上传（用户主动操作，仅本地记录）

dsh 提供消息/会话反馈功能（对回答点赞点踩、提交分类与备注，UI 模块 `dsh-client-ui-message-feedback`），入口为 `/feedback` 命令或消息上的反馈按钮。对其宿主实现的审查结论：

| 项 | 结论 |
|---|---|
| 触发方式 | **仅用户主动点击**反馈按钮/命令，不会自动触发 |
| 数据处理 | `dsh-command-feedback` 的 `feedback/record` 事件原文："appends one authoritative log-only event and does not start model work"（仅追加一条日志事件，不启动模型工作）；`dsh-message-feedback` 为 "Canonical Session-log feedback"（写入 Session 日志） |
| 网络请求 | 两个模块的源码中**均无** `fetch` / `http.request` / 上传端点等网络调用；反馈写入本地 Session sidecar（本地持久化） |
| 与遥测的区别 | 反馈是**用户主动、仅本地记录**；遥测是**自动、发往 DeepSeek 收集端**（且本项目 Android 端不激活）。二者行为不同，不应混为一谈 |

结论：**本项目中的反馈功能不会主动上传任何数据到网络**；它只在本地 Session 日志中记录用户的反馈条目。

### 2.4 本地回环服务

dsh 在设备本机监听 `127.0.0.1`（HTTP），仅用于 App 内 WebView 访问，不对外部网络开放。网络安全配置（`res/xml/network_security_config.xml`）仅对 `127.0.0.1` 与 `localhost` 放行明文流量，其余域名走 HTTPS。

---

## 3. 审查方法与局限

- **方法**：静态审查——检查 Java 源码、AndroidManifest、APK 内 DEX 字符串、dsh.zip 内全部 package.json / 前端 bundle / 遥测模块源码，匹配 URL、端点、开关关键词。
- **局限**：这是静态代码审查，**未做抓包动态验证**；不能排除运行时动态拼接 URL 或第三方依赖未来版本变更。如需最高置信度，建议在设备上用抓包工具（如 mitmproxy）实测。
- **非法律意见**：本文件是事实性技术说明，不构成法律意见。

## 4. 用户建议

- 若不希望产生任何 DeepSeek 官方网络请求，可**不登录、不配置 API Key**——此时应用仅在本机运行。
- 本应用本身无广告 SDK、无崩溃上报、无第三方统计。

---

**English summary**

- The Android shell code communicates only with `127.0.0.1`; no hidden outbound connections, no sensitive permissions.
- The bundled dsh runtime contains DeepSeek's official product-telemetry modules, but they are **desktop-only** and are **not mounted by the Web frontend used here** — static analysis of all 61 frontend bundles found zero references. No telemetry is emitted from this Android app.
- dsh connects to official DeepSeek API domains (`api.deepseek.com`, `harness.deepseek.com`, `platform.deepseek.com`) for login and model calls — core functionality only.
- The user-triggered `/feedback` feature records entries to the **local** Session log only; its host modules contain no network calls. It does not upload anything. This is distinct from the automatic telemetry above.
- Static review; no dynamic packet capture performed. See `DISCLAIMER.md` for non-affiliation notice.
