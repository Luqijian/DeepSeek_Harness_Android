<div align="center">

<img src="docs/logo.svg" width="80" alt="Harness Android logo" />

# Harness Android

在 Android 平板/手机上运行官方 **DeepSeek Harness** 的完整 Web UI 与 Agent 引擎。

全量官方界面 · 本地文件夹工作区 · 真实 PTY 终端 · 自动跟随上游 · 侧载自更新

[简体中文](README.md) · [English](README.en.md)

[![build](https://github.com/Luqijian/DeepSeek_Harness_Android/actions/workflows/build.yml/badge.svg)](https://github.com/Luqijian/DeepSeek_Harness_Android/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/Luqijian/DeepSeek_Harness_Android?include_prereleases&label=release)](https://github.com/Luqijian/DeepSeek_Harness_Android/releases)
[![license](https://img.shields.io/badge/license-MIT-0F766E.svg)](LICENSE)
[![dsh](https://img.shields.io/badge/dsh-0.2.1--rc.2-14B8A6.svg)](https://www.npmjs.com/package/@deepseek-ai/dsh)
[![android](https://img.shields.io/badge/Android-8.0%2B%20arm64-3DDC84.svg?logo=android&logoColor=white)](https://developer.android.com)

</div>

> **非官方项目**：Harness Android 是独立社区作品，与 DeepSeek 无隶属关系。这是 **Developer Edition**：侧载安装、直接访问共享存储，请只打开你信任的项目。

## 简介

Harness Android 不是对 DeepSeek Harness 的重写，也不是一个"远程网页壳"。它把**官方 npm 包 `@deepseek-ai/dsh` 原样**装进设备内置的 Debian 13 arm64 用户态，通过 [proot](https://github.com/termux/proot) 在 Android 应用沙箱里运行，Android 端只负责三件事：

1. 解压并守护 Linux 运行时；
2. 用 WebView 加载官方 Web UI（`127.0.0.1` + 启动 token）；
3. 把你选择的共享存储文件夹 bind 成 Linux 会话的工作区。

因此官方 UI、会话、文件面板、终端、插件、自动化任务都直接来自上游；本项目只提供运行环境、原生交互与更新通道。

## 核心特性

- **完整官方 Web UI** — 会话、工作区、文件树/预览/编辑、终端、插件、自动化任务、模型设置，全部由官方 `dsh` 提供。
- **本地文件夹即工作区** — 通过系统授权打开 `/storage/emulated/0` 下任意目录，proot 直接 bind 到 `/workspaces/default`，AI 的修改实时写回原目录，不复制、不同步。
- **真实终端** — Node-pty 在 Debian 内工作，`bash`、`git`、`rg`、`node`、`npm`/`pnpm` 可直接使用；已处理 Android 的目录所有权与 `core.fileMode` 差异。
- **离线可用** — Debian rootfs、Node 24、官方 dsh 与常见工具链全部打包进 APK；首次启动解压约 1.2 GB，之后无需联网下载运行时。
- **自动跟随上游** — 每日 CI 检查 npm `latest`，自动升级 `dshVersion` 并开 PR；合并后自动构建、签名、发布。应用内"检查更新"直接读取 GitHub Releases 的 `update.json`。
- **无标题栏悬浮球** — 十字球展开工作区/刷新/设置，可全屏拖动、吸附侧边成半透明半球；不再占用屏幕空间。
- **后台常驻** — specialUse 前台服务 + WakeLock，息屏后引擎继续运行，通知栏可停止/重启。
- **账号登录可用** — 官方登录流程使用 `window.open()`，已通过多窗口 + 子 WebView 支持，手机号/验证码登录页可正常显示。

## 界面

| 官方主界面 | 悬浮球菜单 |
| --- | --- |
| ![主界面](docs/screenshots/main.jpg) | ![悬浮球菜单](docs/screenshots/menu.jpg) |

| 真实终端 | 工作区文件面板 |
| --- | --- |
| ![终端](docs/screenshots/terminal.jpg) | ![工作区](docs/screenshots/workspace.jpg) |

| DeepSeek 账号登录 |
| --- |
| ![登录](docs/screenshots/login.jpg) |

## 快速开始

**要求**：Android 8.0（API 26）及以上、arm64 设备、WebView 111+、至少 2.5 GB 可用空间。

1. 从 [Releases](https://github.com/Luqijian/DeepSeek_Harness_Android/releases/latest) 下载最新的 `harness-android-*.apk`。
2. 在系统设置中允许当前文件管理器/浏览器"安装未知应用"，然后安装 APK。
3. 首次启动：阅读安全说明 → 授予通知与"所有文件访问"权限（用于直接打开工作区）。
4. 等待运行时解压（约 30–60 秒，显示文件数与百分比），随后自动进入官方 UI。
5. 在 Harness 的"设置 → 模型"填写 API Key，或使用"登录"通过 DeepSeek 账号授权。

也可以使用 adb：

```bash
adb install -r harness-android.apk
adb shell pm grant io.github.luqijian.harnessandroid.debug android.permission.POST_NOTIFICATIONS
adb shell appops set --uid io.github.luqijian.harnessandroid.debug MANAGE_EXTERNAL_STORAGE allow
```

## 使用指南

- **切换工作区**：点击悬浮球 → 文件夹图标 → 选择 `/storage/emulated/0/...` → 引擎自动重启并绑定该目录。当前工作区会显示在设置页，并持久化。
- **打开终端**：官方 UI 右侧边栏 → "新建终端"，在会话工作区内运行 `bash` 命令。
- **模型与账号**：官方 UI → 设置 → 模型，填写 DeepSeek API Key；或点击"登录"完成 DeepSeek 账号授权。
- **应用更新**：设置 → 检查更新，下载并校验 SHA-256 后调起系统安装器。
- **悬浮球**：单击展开菜单（十字变 X），拖动可自由移动；松手靠近左右边缘时吸附为半透明半球，点击半球恢复。
- **引擎控制**：设置页可启动/停止/重启；通知栏提供停止/重启操作；日志可在设置页分享。

## 架构

```text
┌─────────────────────────────────────────────────────────────┐
│ Android App (Kotlin, WebView, specialUse FGS)                │
│  MainActivity / FloatingMenuView / SettingsActivity          │
└───────────────┬─────────────────────────────────────────────┘
                │ http://127.0.0.1:3080/?token=…
┌───────────────▼─────────────────────────────────────────────┐
│ Bundled runtime (assets, extracted on first launch)          │
│  Debian 13 arm64 → Node 24 → @deepseek-ai/dsh (official)     │
│  proot (Termux, 16 KB aligned) → bind mounts:                │
│    /root/.dsh        ← app files/dsh-home                    │
│    /workspaces/default ← /storage/emulated/0/<your project>  │
│    /tmp, /etc/resolv.conf, /root/documents                    │
└─────────────────────────────────────────────────────────────┘
```

- 官方引擎不做源码修改；适配全部通过 `$DSH_HOME/profiles/web/cordis.patch.yml` 的受管块（`fs-sandbox.cwd`、`sandbox-policy`、`permission.defaultPreset`）与启动环境变量完成。
- proot 主机二进制放在 `jniLibs/arm64-v8a/lib*.so`，使用 `useLegacyPackaging` 解压到 nativeLibraryDir 执行；CI 会校验所有 ELF 的 `p_align ≥ 16 KB`。
- 会话、配置与工作区独立挂载，APK 升级不会清空 `dsh-home`；卸载应用会删除全部本地数据。

## 已验证

| 设备 | 系统 | 结果 |
| --- | --- | --- |
| Xiaomi Pad 6 Pro | Android 15（API 35） | 官方 UI、文件面板、README 预览、终端、账号登录页、悬浮球全部通过 |
| GitHub Actions `ubuntu-24.04-arm` | Debian 13 arm64 | 运行时构建、16 KB ELF 对齐校验、APK 构建通过 |

实测版本：`@deepseek-ai/dsh 0.2.1-rc.2`、Node `24.22.0`、WebView `155.0.8059.43`。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/BUILD.md](docs/BUILD.md) | 运行时构建、APK 构建、签名、CI 发布流程 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 启动链路、proot 挂载、配置层、生命周期与安全边界 |
| [docs/USAGE.md](docs/USAGE.md) | 安装、权限、工作区、终端、账号/API Key、更新、数据位置 |
| [docs/TESTING.md](docs/TESTING.md) | adb 自动化入口、平板验收清单、CI 验证 |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | 黑屏、后台冻结、git 所有权、端口、空间等常见问题 |
| [docs/PRIVACY.md](docs/PRIVACY.md) | 本地优先的数据与隐私说明 |
| [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) | Debian、Node、proot、dsh 等第三方许可 |

## 兼容性与限制

- **平台**：arm64 Android 8.0+（targetSdk 36）；APK 约 349 MB（含运行时），首次解压约 1.2 GB。
- **proot 开销**：系统调用密集操作约慢 40%；Node 冷启动约 10 秒。
- **`/sdcard` 语义**：共享存储为 FUSE，不支持 chmod、符号链接与 hardlink；git/npm 的少数操作会受限。需要完整 POSIX 时，可在终端把项目复制到 `/root` 下操作（不会回写 `/sdcard`）。
- **沙箱**：Android 上官方 Landlock/bwrap 不可用，使用 `danger-full-access`；proot 不是安全边界，应用沙箱是唯一隔离层。
- **桌面专属能力**：Electron 托盘、桌面自更新、macOS Computer Use 不适用；Web 侧栏浏览器与官方 Web 功能可用。
- **未上架 Google Play**：Developer Edition 使用 All-files 权限与 GitHub 自更新，仅通过 Releases 分发。

## 安全

- 引擎仅监听 `127.0.0.1`，并需要启动 token 与签名 Cookie；不默认开放局域网。
- 应用不包含遥测或统计 SDK；API Key 保存在本地 `$DSH_HOME/.credentials.yaml`。
- AI 可执行命令并读写你授权的工作区。请只打开可信项目，为 API Key 设置用量上限，并定期备份重要文件。
- 发现安全问题请通过 [SECURITY.md](SECURITY.md) 私下报告，不要公开提交可利用细节。

## 开发与贡献

```bash
# 1) 构建运行时（Linux / arm64 runner）
bash runtime/build-runtime.sh
cp runtime/out/rootfs.tar.gz app/src/main/assets/runtime/rootfs.bin
cp runtime/out/manifest.json app/src/main/assets/runtime/manifest.json
cp runtime/out/jniLibs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/

# 2) 构建 APK（JDK 17 + Android SDK 36）
./gradlew :app:assembleDebug
```

- 默认 `dshVersion` 在 `gradle.properties`；`upstream-sync` 工作流每日检查 npm 并自动开 PR。
- 适配优先放在配置层（`cordis.patch.yml` 受管块）与运行环境变量，避免修改上游源码；必须打补丁时请更新 `docs/ARCHITECTURE.md` 并在 PR 中说明。
- 不要提交运行时 blob、keystore、`local.properties` 或任何 Token。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 许可证

本项目以 **MIT** 许可发布，© 2026 Luqijian。内置的 Debian、Node.js、proot、`@deepseek-ai/dsh` 等组件遵循各自许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
