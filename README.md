# Harness Android (DeepSeek Harness, unofficial)

在 Android 平板/手机上运行官方 [DeepSeek Harness (`@deepseek-ai/dsh`)](https://github.com/deepseek-ai/deepseek-harness) 的完整 Web UI 与 Agent 引擎。不修改引擎源码，通过 proot 用户态与最小配置补丁实现，便于随上游 npm 版本自动更新。

> 这是独立社区项目，与 DeepSeek 无隶属关系。开发者版（Developer Edition），面向个人设备，直接访问共享存储，请只打开你信任的项目。

## 功能

- **完整官方 Web UI**：会话、工作区、文件面板、终端（真实 PTY）、插件、自动化任务，全部由官方 npm 包 `@deepseek-ai/dsh` 提供。
- **本地文件夹作为工作区**：选择 `/storage/emulated/0` 下的任意文件夹，直接以 bind 方式挂载到 Linux 会话的 `/workspaces/<id>` 与 `/workspaces/default`，AI 对文件的修改实时写回原目录。
- **自动跟随上游**：`upstream-sync` workflow 每日检查 npm `latest`，自动升级 pin 并重建；APK 通过 GitHub Release 自更新（`update.json` + SHA-256 校验）。
- **后台常驻**：specialUse 前台服务 + WakeLock，息屏后引擎继续运行，通知栏可停止/重启。

## 架构

```
WebView ── http://127.0.0.1:3080/?token=… ── dsh web（官方引擎，Node 24）
                                              │ proot（Termux 构建，16KB 对齐）
                                     Debian 13 arm64 rootfs（assets 内置，首次解压）
                                              │ bind
                        /storage/emulated/0/<你的项目> → /workspaces/…
```

- 引擎与工具链（git、rg、bash、pnpm、编译器）全部打包进 APK，运行时不需要网络下载可执行文件。
- `HOME=/root`、`$DSH_HOME`、`/tmp`、`/etc/resolv.conf` 与工作区目录均通过 proot bind 指向应用私有目录；运行时升级不影响会话与配置。
- dsh 配置（`$DSH_HOME/profiles/web/cordis.patch.yml`）由 App 以受管块写入：`fs-sandbox.cwd`、`sandbox-policy.mode: danger-full-access`、`workspace-controller.documentsDirectory`、`permission.defaultPreset`。Android 无 Landlock/bwrap，沙箱依赖 Android 应用沙箱边界。

## 构建本地版本

```bash
# 1) 构建 Linux 运行时（Linux 主机或 CI，见 runtime/build-runtime.sh）
bash runtime/build-runtime.sh
cp runtime/out/rootfs.tar.gz app/src/main/assets/runtime/rootfs.bin
cp runtime/out/manifest.json app/src/main/assets/runtime/manifest.json
cp runtime/out/jniLibs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/

# 2) 构建 APK（需要 JDK 17 + Android SDK 36）
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI（`.github/workflows/build.yml`）在 `ubuntu-24.04-arm` 上构建运行时并校验 16 KB 页对齐，随后产出 debug/release APK；打 `v*` tag 时自动发布 Release 并附带 `update.json` 与 `SHA256SUMS`，供应用内“检查更新”使用。

## 使用

1. 首次启动阅读安全说明并同意；等待运行时解压（约 30–60 秒）。
2. 工具栏“工作区”按钮 → 选择 `/storage/emulated/0/` 下的文件夹 → 引擎自动重启并挂载。
3. 在 Harness 界面“设置 → 模型”填写 API Key，即可开始对话。

## 已验证（小米平板 6 Pro，Android 15，arm64）

- `dsh 0.2.1-rc.2` 全功能启动：Web UI、token 登录、文件面板、README 预览。
- 终端真实 PTY：`pwd`、`node src/index.js`、`git status` 在直连目录中执行成功（已全局 `safe.directory=*`、`core.fileMode=false`）。
- 运行时解压 51702 个文件，SHA-256 校验通过；息屏后服务保持运行。

## 限制

- proot 有约 40% 的 syscall 开销；`/sdcard` 为 FUSE，不支持 chmod、符号链接与 hardlink（git/npm 的少数操作受限，文档有说明）。
- 官方 Landlock/bwrap 沙箱在 Android 不可用，使用 `danger-full-access`；应用沙箱是唯一隔离边界。
- arm64 单架构；Android 8.0（API 26）+，WebView 需 Chrome 111+。

## 许可

本项目 MIT。内置的 Debian、Node.js、proot 与 `@deepseek-ai/dsh` 遵循各自的开源许可，见 `docs/LICENSES.md`。
