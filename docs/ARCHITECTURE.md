# 架构说明

Harness Android 是一个**宿主壳**，不是 DeepSeek Harness 的 fork。官方 Web UI 与 Agent 引擎以 npm 包原样运行在设备内置的 Debian 用户态里，Android 只提供运行时、窗口和文件桥接。

## 总览

```text
MainActivity (WebView / FloatingMenuView / 权限与引导)
      │ 观察 EngineController.state
EngineService (specialUse FGS + WakeLock + 通知)
      │
EngineController (单例，串行启动/停止/重启)
      ├── RuntimeInstaller  解压 assets/runtime/rootfs.bin → files/runtime/<version>/rootfs
      ├── WorkspaceStore    当前工作区（DIRECT）
      ├── DshConfig         写 $DSH_HOME/profiles/web/cordis.patch.yml 受管块
      └── ProotLauncher     构造 proot 命令/环境/launcher 脚本
                              │
                        proot (libproot.so) + loader
                              │  Debian 13 arm64 rootfs
                              │  Node 24 → @deepseek-ai/dsh web --host 127.0.0.1 --port 3080
                              ▼
                        http://127.0.0.1:3080/?token=…
                              │
                        WebView 加载官方 UI
```

## 启动链路

1. **首次启动**：`RuntimeInstaller` 从 `assets/runtime/rootfs.bin` 读取 gzip 流，按 `manifest.json` 的 SHA-256 校验，解压到 `files/runtime/extract-*`，保留符号链接/权限，完成后原子重命名为 `files/runtime/<version>/rootfs`，写入 `.complete` 标记。
2. **工作区**：`WorkspaceStore.active()`；没有则创建默认私有工作区。开发版只暴露 `DIRECT` 模式：用户选中的真实文件夹作为 `backendPath`。
3. **配置**：`DshConfig` 在 `files/dsh-home/profiles/web/cordis.patch.yml` 写入受管块（见下），并在 `files/documents/by-workspace/<id>/deepseek-harness/` 下把 `default-workspace` 符号链接到 guest 的 `/workspaces/<id>`。
4. **进程**：`ProotLauncher` 生成 `/system/bin/sh files/bin/start-engine.sh <pidfile> <proot> ...`。launcher 先写 PID，再 `exec setsid`，使 proot 成为进程组组长，便于整组回收。
5. **就绪**：`EngineController` 持续读取 stdout，匹配 `http://127.0.0.1:<port>/?token=...`，把 `EngineState.Ready(url, port)` 推给 UI；MainActivity 只在 URL 变化时 `loadUrl`。
6. **登录 token**：dsh 在首次 `GET /?token=…` 时交换签名的 HttpOnly Cookie，然后 303 到 `./`；WebView 之后使用 Cookie 访问 `/api` 与 WebSocket。
7. **停止**：读取 pid 文件，对进程组发 SIGTERM，等待 2.5 秒后 SIGKILL，并删除 pid；启动前还会 `cleanupOrphans()` 清理上一个进程残留（应用被强停/重装时尤其重要）。

## proot 挂载与启动参数

```text
proot --link2symlink -r <rootfs> \
  -b /dev -b /proc -b /sys \
  -b files/dsh-home:/root/.dsh \
  -b files/documents/by-workspace/<id>:/root/documents/<id> \
  -b <真实文件夹>:/workspaces/<id> \
  -b <真实文件夹>:/workspaces/default \
  -b files/tmp:/tmp \
  -b files/tmp/resolv.conf:/etc/resolv.conf \
  -0 -w /workspaces/<id> \
  /usr/bin/env HOME=/root PATH=... DSH_HOME=/root/.dsh \
    DSH_PERMISSION_MODE=danger-full-access \
    GIT_CONFIG_GLOBAL=/root/.dsh/gitconfig \
    node --expose-internals .../dsh/lib/bin.js web --no-open --host 127.0.0.1 --port 3080
```

- 同时绑定 `/workspaces/<id>` 与 `/workspaces/default`：dsh 首次启动会把默认工作区持久化为 `/workspaces/default`，两个路径指向同一个真实目录，切换工作区后旧的 UI 会话与文件面板仍然一致。
- `-0` 伪造 root；host 侧文件实际属于应用 UID。`--link2symlink` 让 proot 用符号链接模拟 hardlink，绕过 Android 对 `link(2)` 的限制。
- `PROOT_LOADER`、`PROOT_TMP_DIR`、`LD_LIBRARY_PATH` 指向 nativeLibraryDir 与 `files/tmp/proot`；proot 二进制从 `lib*.so` 解压到 nativeLibraryDir 后执行。
- 所有 ELF 需 16 KB 对齐（Termux NDK 28+ 构建；CI 校验 `LOAD.p_align ≥ 0x4000`）。

## 配置层（不改上游源码）

`files/dsh-home/profiles/web/cordis.patch.yml` 由 App 以受管块写入，启动前重新应用：

```yaml
# >>> harness-android managed >>>
- id: fs-sandbox
  config:
    cwd: '/workspaces/<id>'
- id: sandbox-policy
  config:
    mode: danger-full-access
- id: workspace-controller
  config:
    documentsDirectory: '/root/documents/<id>'
- id: permission
  config:
    defaultPreset: danger-full-access
# <<< harness-android managed <<<
```

- `DSH_PERMISSION_MODE=danger-full-access` 同时让 base 层的 sandbox mode 和 approval policy 组合成合法 preset，避免 `permission-presets` 报 "match no preset"。
- `GIT_CONFIG_GLOBAL` 指向 `dsh-home/gitconfig`，写入 `safe.directory = *` 与 `core.fileMode = false`，解决 `/sdcard` 文件所有者与可执行位带来的 git 报错。
- 其他设置（模型、插件、会话）由官方 UI 写入同一 profile 目录；受管块只占固定区间，保留用户配置。

## 工作区与文件语义

- 开发版只提供**直连**：`/storage/emulated/0/<project>` 通过 proot bind 进入 guest，AI 的写入实时落到原目录。
- 共享存储是 FUSE：不支持 chmod、符号链接、hardlink；`git`/`npm` 的少数操作（如 `.bin` 链接）会失败。需要完整 POSIX 时，在终端把项目复制到 `/root` 下操作（不会回写 `/sdcard`）。
- 官方 Web UI 的"默认工作区"以 `/workspaces/default` 显示；切换工作区会停止引擎、重写受管配置、重启并重新加载 WebView。
- 仓库中仍保留 `SafWorkspaceImporter` / `SafSyncEngine`（SAF 镜像与双向同步）代码，但开发版 UI 不暴露；这是为后续可能的专用版本保留的实验代码。

## 生命周期与保活

- `EngineService` 是 `specialUse` 前台服务，启动时立即 `startForeground`，常驻通知提供停止/重启；`START_STICKY`、`stopWithTask=false`、`onTaskRemoved` 不停止引擎。
- 持有 `PARTIAL_WAKE_LOCK`；但 MIUI/HyperOS 等 ROM 仍可能在无界面时冻结进程（`do_freezer_trap`）。把应用设为"无限制"并允许自启动可避免；这属于 ROM 行为，不是应用崩溃。
- 引擎意外退出会把最后 60 行日志推入 `EngineState.Error`，UI 显示重试；启动 150 秒未收到 token 也会判定超时并杀进程。

## WebView 集成

- 只允许 `127.0.0.1` / `localhost` 留在 WebView 内；其他 URL 交给系统浏览器。
- 启用 DOM storage、数据库、第三方 Cookie；`mixedContentMode=NEVER_ALLOW`；`network_security_config` 仅对 loopback 放行明文。
- 文件上传：`onShowFileChooser` → 系统文件选择器；下载：`DownloadManager` 写到应用外部下载目录；麦克风：运行时权限 + `onPermissionRequest`。
- 账号登录：dsh 使用 `window.open()`，WebView 开启 `setSupportMultipleWindows(true)`，`onCreateWindow` 创建独立子 WebView 放入全屏 Dialog，与主页共享 Cookie；`window.close()` 通过 `onCloseWindow` 关闭。关闭多窗口会导致主页面变成 `about:blank`（黑屏）。
- 返回键优先 `WebView.goBack()`，否则退出；WebView 崩溃/渲染进程退出可通过重新进入应用恢复。

## 悬浮球

`FloatingMenuView` 是一个 **56 dp 宽** 的自绘 View，高度容纳 3 个菜单项；用绝对屏幕坐标（`translationX/Y`）定位，而不是 layout margin/gravity：

- 收起时画 52 dp 十字圆球；点击展开，球变 X，上方/下方按空间绘制 3 个同尺寸动作圆球（工作区/刷新/设置）。
- 触摸命中测试只覆盖球和菜单项；其他区域的 `onTouchEvent` 返回 `false`，事件继续落到下面的 WebView，因此不会挡聊天界面。
- 拖动同时更新 x/y；松手距左右边缘 ≤56 dp 时吸附为半透明半球（中心贴边，露出半圆）；点击半球恢复。
- 位置与吸附状态写入 SharedPreferences；旋转/重启后恢复到合法范围。

## 安全边界

- 引擎仅监听 loopback，需要 token + 签名 Cookie；不默认开 LAN。
- Android 无 Landlock/bwrap，运行在 `danger-full-access`；**proot 不是安全边界**，应用 UID/Android 沙箱才是。
- 应用申请 All-files 权限用于打开工作区；不会主动扫描未选择的目录。
- 无遥测 SDK；API Key 保存在本地 `$DSH_HOME/.credentials.yaml`；模型请求直接发往用户配置的服务商。

## 已知取舍

| 取舍 | 说明 |
| --- | --- |
| proot 性能 | syscall 密集操作约慢 40%，Node 冷启动约 10 秒；换来无需 root/无需 targetSdk 28 |
| APK 体积 | 约 349 MB，首次解压约 1.2 GB；运行时完全离线自包含 |
| 单 ABI | 只支持 arm64-v8a；x86_64 模拟器需另行构建运行时 |
| `/sdcard` 语义 | FUSE 不支持 chmod/符号链接/hardlink，部分 git/npm 操作受限 |
| 后台冻结 | 前台服务 + WakeLock 仍可能被激进 ROM 冻结，需要用户设置电池策略 |
