# 第三方组件声明 / Third-Party Notices

DshWebView Android Shell 打包或依赖了以下第三方组件。本项目自身的许可（PolyForm Noncommercial 1.0.0）**仅覆盖本项目编写的代码与构建脚本**，不影响这些组件各自的许可证。请在重新分发时一并遵守它们的条款。

关于非官方关系、商标使用与责任边界，另见 [`DISCLAIMER.md`](DISCLAIMER.md)。

## 1. DeepSeek Harness（dsh）运行时与 Web 前端

- **来源**：npm 包 `@deepseek-ai/*`（版本 `0.2.2-rc.5`），作为 `assets/runtime/dsh.zip` 打包进 APK。
- **许可**：MIT License
- **版权**：Copyright (c) 2026 DeepSeek
- **许可全文**：MIT 标准文本，见 <https://opensource.org/license/mit>。原包内附带的 `LICENSE` 文件已保留在构建产物中。
- **说明**：MIT 许可允许使用、复制、修改、合并、发布、分发、再许可和/或销售，条件是保留版权声明与许可声明。本项目保留了完整的 dsh 包内容与原始声明。dsh 商标与 "DeepSeek" 名称归 DeepSeek 所有，本项目与其无隶属关系。

## 1b. dsh 依赖树中的第三方 npm 包

`dsh.zip` 内还包含 dsh 依赖的完整 node_modules 树（约 1409 个 package.json）。经全量审查，许可分布如下，均为宽松或常见许可，**未发现 GPL / AGPL / SSPL / BUSL 等强传染性或商业限制许可**：

| 许可 | 包数量 | 说明 |
|---|---:|---|
| MIT | 580 | 如 zod、@babel/runtime 等 |
| Apache-2.0 | 54 | 如 @google/genai（自带 LICENSE）、sharp |
| BSD-3-Clause / BSD-2-Clause | 17 | 宽松许可 |
| ISC | 12 | 宽松许可 |
| LGPL-3.0-or-later | 2 | `@img/sharp-libvips-*`，详见第 1c 节 |
| Python-2.0 | 1 | `argparse`，宽松 |
| BlueOak-1.0.0 | 1 | `lru-cache`，宽松 |
| Unlicense / 0BSD / (MIT OR CC0-1.0) | 各 1 | 宽松/公有领域 |

> 说明：统计中另有约 739 个"未声明 license"条目，绝大多数是 `zod`、`@babel/runtime` 等包内部的 **exports 子路径 package.json**（如 `zod/v4/core/package.json`、`@babel/runtime/helpers/esm/package.json`），它们本身不是独立包，其父包均已声明 MIT 许可。少数如 `@local/my-decoration`、`@local/demo-mcp` 为 dsh 自带的本地演示插件。

## 1c. sharp / libvips（LGPL-3.0，需重点注意）

- **来源**：`@img/sharp-libvips-linux-arm64`、`@img/sharp-libvips-linuxmusl-arm64` v1.3.4，含 `libvips-cpp.so.8.18.7`（约 18 MB ×2）；由 `sharp` v0.35.5（Apache-2.0）在运行时按需加载。
- **许可**：libvips 为 **LGPL-3.0-or-later**；sharp 与 `@img/sharp-linux-*` 原生模块为 Apache-2.0（其 LICENSE 全文已随 dsh.zip 分发）。
- **合规措施**：本项目未修改这些库；它们以独立 `.so` 形式随包分发、运行时动态加载，用户可用接口兼容的修改版替换。为履行 LGPL §4（Combined Works）义务，本项目：
  1. 在本文件中给出显著声明（prominent notice），说明使用了 libvips 及其受 LGPL 约束；
  2. 随仓库附带 GNU GPL-3.0 与 LGPL-3.0 全文：[`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt)、[`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt)；
  3. 提供 libvips 源码获取途径：上游 <https://github.com/libvips/libvips>（版本 8.18.7），或预编译包来源 <https://github.com/img/sharp-libvips>。
- **说明**：该库在 Android（bionic）环境下通常无法加载（sharp 面向 glibc/musl Linux），其被打包仅为 dsh 依赖树的完整性；但无论是否实际运行，本项目均按 LGPL 要求提供上述声明与文本。

## 2. Node.js（libnodeexec.so 原生运行时）

- **来源**：Node.js `26.13.1` aarch64 deb（nodejs 官方发行），编译为 `libnodeexec.so` 置于 `app/src/main/jniLibs/arm64-v8a/`。
- **许可**：MIT License（Node.js 采用 MIT；其内置依赖各自有 BSD / ISC / MIT 等许可，详见 Node.js 源码发行中的 `LICENSE` 与 `deps/*/LICENSE`）。
- **参考**：<https://github.com/nodejs/node/blob/main/LICENSE>

## 3. Termux bash 及依赖库

- **来源**：Termux 社区 deb 包，位于 `tools/termux_debs/`，解压后打入 `assets/shellruntime.zip`：
  - `bash` 5.3.20 —— 许可 GPL-3.0-or-later
  - `readline` 8.3.6 —— 许可 GPL-3.0-or-later
  - `libiconv` 1.19.1 —— 许可 LGPL-2.1-or-later / GPL-3.0（依构建而定）
  - `ncurses` —— 许可 X11 风格（MIT-like）
  - `libandroid-support` —— 许可 Apache-2.0 / 公共领域（依组件而定）
- **参考**：<https://github.com/termux/termux-packages>
- **说明**：GPL/LGPL 组件以二进制形式随应用分发；如需获取对应源码，请访问上述 Termux 官方仓库的对应 package 版本。

## 4. Shizuku（API 与 Provider）

- **来源**：`moe.shizuku:api:13.1.5`、`moe.shizuku:provider:13.1.5`，位于 `app/libs/*.aar`；AIDL 接口文件从 Shizuku-API 项目取得并编译。
- **许可**：Apache License 2.0
- **版权**：Copyright 2016-2026 Shizuku contributors
- **参考**：<https://github.com/RikkaApps/Shizuku>

## 5. Android SDK / WebView / Java 标准库

- 本项目使用 Android 官方 SDK 与系统 WebView，遵循 Android SDK 许可与各自平台条款。

---

## 许可冲突说明

本项目的 PolyForm Noncommercial 1.0.0 许可**不适用于**上述第三方组件。它们保持各自的原始许可证（多为 MIT / Apache-2.0 / GPL 等，其中多数允许商业使用）。你若要将整个应用用于商业目的，需要同时满足：

1. 获得本项目许可人的商业授权（PolyForm NC 不授予任何商业权利）；并且
2. 遵守所有第三方组件各自的许可要求。

本项目不对第三方组件的权利状态作任何额外承诺；请以各组件官方声明为准。
