# Changelog

## 0.1.0 — 2026-10-18

首个 Developer Edition。

### 新增

- 在 Android 上运行官方 `@deepseek-ai/dsh` Web UI：内置 Debian 13 arm64、Node 24.22.0、官方 npm 包与 proot。
- 首次启动解压运行时（~1.2 GB），SHA-256 校验，原子切换；支持 Windows/macOS 侧的 CI 构建。
- 将任意 `/storage/emulated/0` 文件夹直接 bind 为工作区（`/workspaces/default`）。
- 真实 PTY 终端；git 的 `safe.directory` 与 `core.fileMode` 适配。
- specialUse 前台服务 + WakeLock；通知栏停止/重启；引擎异常退出日志。
- 无标题栏悬浮球：十字/收缩、全屏拖动、左右吸附半透明半球、点击穿透。
- DeepSeek 账号登录弹窗（多窗口子 WebView），修复 `about:blank` 黑屏。
- GitHub Releases 自更新：`update.json` + SHA-256 校验 + 系统安装器。
- CI：arm64 运行时构建、16 KB ELF 对齐校验、APK 构建、Release 发布；每日上游 npm 同步 PR。
- 文档：中文/英文 README、构建、架构、使用、测试、排障、隐私、第三方许可。

### 修复

- 首次解压进度为 0：改用 `manifest.json` 的压缩包大小作为进度分母，并显示百分比。
- 悬浮球垂直方向无法拖动、被方形边界裁切、菜单图标过小：改为小视图 + 绝对坐标定位、统一 52dp 尺寸、去掉描边。
