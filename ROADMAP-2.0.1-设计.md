# DshWebView 2.0.1 实施设计（主线：MCP 工具层）

> 这是实施蓝图，基于 2026-11-22 对 dsh 运行时的静态审查。
> 目标：能力自己实现、工具集自己设计，不照抄任何现成项目方案。
> 配套文件：`ROADMAP-2.0.1-评估.md`（可行性表）。

---

## 1. 已确认的技术底座（实测，非推测）

| 项 | 结论 | 证据位置 |
|---|---|---|
| DSH 有 MCP 接入 | `dsh-mcp-client` + `dsh-mcp-manager`，工具注册为 `mcp__<serverName>__<toolName>` | `dsh/node_modules/@deepseek-ai/dsh-mcp-client/src/index.ts` |
| 官方推荐 transport | `streamable-http`，URL `http://127.0.0.1:3000/mcp` | `dsh-agent-preset/skills/dsh-doc/templates/mcp/cordis.patch.yml` |
| 挂载方式 | 启动参数加 `--patch <yml>`，patch 用 `- insert:` 列表插入插件 | 官方 MCP 模板 + `dsh-experimental-inspector-profile/cordis.patch.yml` |
| 两个启动点 | 普通模式 `DshService`、shell 模式 `ShizukuLauncher`，都用 `--profile web` | 对应 Java 源码 |
| MCP 协议版本 | 客户端 `@modelcontextprotocol/client` 2.2.0，支持到 revision `2026-07-28`；较新版本要求 `_meta` envelope | `client/dist/src-xKF0PJN-.mjs` |
| 包内 SDK | 只有 `client` + `core`，**没有 server SDK** | 遍历 `@modelcontextprotocol/*/package.json` |
| 图片输入 | dsh 内置附件管线 + `dsh-llm-deepseek` 图片处理；DeepSeek `deepseek-flash` 原生多模态 | `dsh-attachment`、`dsh-llm`、`dsh-llm-deepseek` 源码 + 官方 API 文档 |

## 2. 主线架构

```
[Android 原生能力]  Java 层 (App 进程, 有 Android 权限)
  电量/震动/剪贴板/通知/文件/摄像头
          |
          | 本地 IPC / HTTP
          v
[Android Device Bridge]  Java 层起本地 HTTP server (127.0.0.1:3001)
  提供 /tool/<name> 之类的本地端点
          |
          | localhost HTTP
          v
[MCP server]  Node 进程 (在 dsh 的 libnodeexec.so 环境下, 127.0.0.1:3000/mcp)
  实现 MCP streamable-http 协议, 把本地端点包装成 MCP tools
          |
          | dsh-mcp-client 连接
          v
[DSH 工具系统]  mcp__android__<toolName>
          |
          v
[DeepSeek 模型]  通过 tool call 使用这些能力
```

**为什么是这个架构：**

1. **streamable-http 而非 stdio**：stdio 要 spawn 子进程，在 Android shell uid 环境下权限/路径都麻烦；官方模板也用 streamable-http。
2. **Java 层做能力、Node 层做协议**：Android 权限（摄像头、震动等）只有 Java 层有；Node 只负责协议转换，职责清晰。
3. **不改 dsh 核心**：只加一个 `--patch` 参数和一个 patch 文件，dsh 升级时不会冲突。

## 3. 分步实施计划

### 步骤 A：最小打通（POC，最先做）
1. Java 层起一个最简单的本地 HTTP server，只提供一个 `ping` 端点。
2. Node 层写一个最小 MCP server（实现 `initialize` + `tools/list` + `tools/call`），把 `ping` 暴露为工具。
3. 生成 patch yml，启动 dsh 时加 `--patch`。
4. 在 dsh 界面确认能看到 `mcp__android__ping` 工具并能调用。

**验收标准**：模型能调用 ping 工具并拿到返回。这一步通了，后面所有能力都是往里加工具。

### 步骤 B：安全工具集（主线内容）
在 POC 基础上加工具，每个都独立可测：

| 工具名（设计） | 能力 | 权限 | 风险 |
|---|---|---|---|
| `get_battery` | 电量/充电状态 | 无特殊权限 | 低 |
| `vibrate` | 震动反馈 | `VIBRATE` | 低 |
| `get_clipboard` | 读剪贴板 | 前台读取限制 | 低（注意 Android 10+ 前台限制） |
| `notify` | 发通知 | `POST_NOTIFICATIONS`（已有） | 低 |
| `pick_file` | 用户选文件 | SAF（用户主动选取） | 低 |

**原创点**：工具命名、参数 schema、返回格式、权限提示文案，全部自己设计。

### 步骤 C：摄像头图片问答
1. `capture_photo` 工具 → Java 层 CameraX 拍照 → 存应用私有目录。
2. 工具返回图片附件引用 → 走 dsh 附件管线 → `deepseek-flash` 多模态。
3. 验收：拍一张照，模型能描述内容。

### 步骤 D：折中能力
- `transcribe`：按键说话 → 端侧 ASR（sherpa-onnx，最通用方案，Apache-2.0，需加许可声明）→ 文字送入 dsh。不做常驻监听/打断。
- `screenshot`：用户主动触发截图 → 走图片管线。不碰 Accessibility 自动读屏。

### 步骤 E：版本与发布
- versionCode 3 / versionName 2.0.1
- 更新 README、THIRD_PARTY_NOTICES（sherpa-onnx）、PRIVACY（新增能力的数据流）
- 完整编译 + 真机验证 + 上传 Release

## 4. 已识别的坑（提前记下）

1. **MCP 协议版本**：client 支持到 2026-07-28，新协议 `_meta` envelope 有要求。手写 server 要精确匹配；若匹配困难，考虑把 server SDK 打进包（增加体积）。**POC 阶段优先验证协议握手能否成功**。
2. **Node 环境**：server 跑在 `libnodeexec.so` 下，要用 `--require android-compat.cjs` 的 shim；端口不要和 dsh 的 3080 冲突（规划 3000 MCP / 3001 能力桥）。
3. **剪贴板限制**：Android 10+ 只有前台应用能读剪贴板，需要检查 App 是否处于前台。
4. **CameraX 依赖**：需加 AndroidX 依赖，但当前项目 `android.useAndroidX=false`——要么改 true 并处理兼容，要么用原生 Camera2。**这是个需要决策的点**。
5. **sherpa-onnx 体积**：模型文件 + native 库可能几十 MB，会显著增大 APK（当前已 216MB）。**建议做成可选下载而非打包进 APK**。

## 5. 待用户决策

- CameraX vs Camera2（涉及 AndroidX 迁移）
- sherpa-onnx 打包进 APK 还是运行时下载
- MCP server 协议实现：手写 vs 打入 server SDK

> 这些决策在步骤 A（POC）之后再做也不迟——先用最小成本验证协议通路。
