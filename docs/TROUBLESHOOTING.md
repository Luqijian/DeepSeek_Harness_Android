# 故障排查

## 登录页面全黑 / 变成 about:blank

**已修复**（commit `1c592fb`）。dsh 的账号登录使用 `window.open()`；旧版本关闭了 WebView 多窗口，Chromium 无法创建弹窗，把主页面导航到了 `about:blank`。

- 升级到最新 APK；
- 点击"登录"后应出现全屏登录弹窗（顶部有"取消"），页面是手机号/验证码登录；
- 若仍是黑屏：确认 Android System WebView ≥ 111；查看日志是否有 `popup requested` / `auth popup shown`；
- 若登录页能打开但完成后主界面未刷新：返回主界面重新进入，或重启引擎（设置 → 重启）。

## 主界面整体黑屏，但悬浮球可见

多数是 WebView 页面加载失败或 401：

1. 下拉/点击悬浮球 → 刷新；
2. 查看 `files/logs/engine.log` 是否有 `dsh web: http://127.0.0.1:3080/?token=...` 与 `webview loaded`；
3. 若只有 401，说明 token 过期或页面被导航到无 token 的 `/`：重启引擎；
4. 若伴随 `ERR_CONNECTION_REFUSED`，进程未启动或端口被占用。

## 引擎一直停在"启动中"或反复重启

- 检查是否有旧进程占用 3080：正常停止/重启会清理；应用被强停后下次启动会读 pid 文件并清理旧进程组。
- 查看日志尾部：`adb shell run-as io.github.luqijian.harnessandroid.debug tail -n 80 files/logs/engine.log`。
- 常见原因：运行时解压不完整（重新安装/清理 `files/runtime` 后重启）、磁盘不足、proot 被 ROM 限制。
- 首次启动 150 秒未收到 token 会超时；冷启动正常约 10 秒。

## 首次解压进度不动 / 一直 0%

- 旧版本分母为 0；升级到最新版（进度分母来自 `manifest.json`，会显示百分比）。
- 若长时间 0%：检查可用空间（至少 2.5 GB）、是否杀进程；日志 tag `runtime`。
- 删除 `files/runtime` 后重新启动会重新解压（会重新占用约 1.2 GB）。

## 后台任务被冻结（MIUI / HyperOS / 其他 ROM）

现象：切到后台或息屏一段时间后，Web UI 的 WebSocket 断开、终端卡住，日志出现 `do_freezer_trap` 或进程被暂停。前台服务与 WakeLock 已启用，但部分 ROM 仍会冻结。

处理：

1. 设置 → 应用 → Harness → 电池 → **无限制**；
2. 允许自启动/后台运行；
3. 在最近任务里锁定应用；
4. 保持常驻通知（不要划掉）；
5. 需要长时间任务时保持充电/屏幕常亮。

这是 ROM 策略，不是应用崩溃；回到前台后引擎通常会自动恢复。

## `git status` 报 dubious ownership

应用已在 `files/dsh-home/gitconfig` 配置 `safe.directory = *` 和 `core.fileMode = false`，正常无需处理。若你自定义了 `GIT_CONFIG_GLOBAL` 或使用其他仓库：

```bash
git config --global --add safe.directory '*'
git config --global core.fileMode false
```

## `/sdcard` 上的 npm / git 报错

共享存储为 FUSE：

- 不支持 chmod/可执行位、符号链接、hardlink；
- `npm install` 的 `.bin` 链接、部分 git 操作可能失败；
- 解决：在终端把项目复制到 `/root` 下操作（完整 POSIX，但不会回写 `/sdcard`），或把项目放在应用私有目录。

## 端口/进程冲突

- 引擎固定使用 `127.0.0.1:3080`；同一时间只应有一个实例。
- 设置页"停止引擎"后重新启动；若异常，强制停止应用后重新打开（启动时会清理残留进程组）。
- 终端查看进程：`ps -A -o PID,ARGS | grep -E 'libproot|dsh/lib/bin'`。

## 应用无法安装/覆盖

- 前后 APK 签名必须一致；debug 与 release、不同来源的 APK 不能互相覆盖。
- 先备份数据，再卸载旧包安装新包（会清空应用私有数据）。
- 部分 ROM 需要关闭"纯净模式/安装校验"（例如 MIUI 的 `verifier_verify_adb_installs` 仅影响 adb 安装）。

## 更新检查失败

- GitHub API 未登录时限流 60 次/小时；稍后重试。
- 确认 Release 同时包含 APK 与 `update.json`；缺少任一都会报错。
- 网络需要能访问 `api.github.com` 与 `github.com`；也可手动下载 APK 覆盖安装。

## WebView 版本过旧

要求 Chrome/WebView 111+。在应用商店更新 **Android System WebView** 或 Chrome；旧 WebView 可能无法运行官方 React UI。

## 运行时资源缺失

如果你是从源码构建 APK 但没放入运行时，会看到"运行时资源缺失"。按 [BUILD.md](BUILD.md) 生成并复制 `rootfs.bin`、`manifest.json` 与 jniLibs，或直接使用 CI/Releases 的 APK。

## 如何收集日志

- 设置页 → 分享日志；
- 或：

```bash
adb shell run-as io.github.luqijian.harnessandroid.debug \
  cat files/logs/engine.log > engine.log
```

日志包含 runtime/config/launch/engine/dsh/webview 标签，提交 Issue 时请附上设备型号、Android 版本、WebView 版本与 dsh 版本。
