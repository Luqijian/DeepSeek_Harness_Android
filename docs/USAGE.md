# 使用指南

## 安装

1. 从 [Releases](https://github.com/Luqijian/DeepSeek_Harness_Android/releases/latest) 下载 `harness-android-*.apk`。
2. 系统设置 → 安全 → 允许当前文件管理器/浏览器安装未知应用，然后安装。
3. 首次打开阅读安全说明；同意后授予通知权限与"所有文件访问"权限。

调试包名为 `io.github.luqijian.harnessandroid.debug`，release 包名为 `io.github.luqijian.harnessandroid`；两者数据独立，不能互相覆盖安装。

## 首次启动

1. 应用解压内置运行时（约 1.2 GB，30–60 秒），界面显示文件数与百分比。
2. 解压完成后自动启动 dsh，WebView 加载官方 UI。
3. 通过以下任一方式开始：
   - **API Key**：官方 UI → 设置 → 模型 → 填入 DeepSeek API Key；
   - **账号登录**：官方 UI → 登录 → 手机号/验证码或密码登录（弹窗内完成）。

## 打开本地文件夹作为工作区

1. 点击右下角悬浮球 → 文件夹图标（或设置页 → 打开本地文件夹）。
2. 首次会请求"所有文件访问"权限；授予后进入目录浏览器。
3. 导航到目标文件夹（例如 `/storage/emulated/0/Download/MyProject`），点击"选择此文件夹"。
4. 引擎自动停止并重启；官方 UI 的文件面板、终端、Agent 都会以该文件夹为工作区。
5. 选择会持久化；下次启动自动使用，直到再次切换。

注意：共享存储是 FUSE，不支持 chmod、符号链接和 hardlink。需要完整 POSIX 语义时，在终端把项目复制到 `/root` 下操作（不会回写 `/sdcard`）。

## 终端

官方 UI 右侧边栏 → "新建终端"，默认在会话工作区启动 `bash`。可执行：

```bash
pwd            # /workspaces/default
ls
node src/index.js
git status
npm install    # 注意 /sdcard 的 FUSE 限制
```

终端是真实 PTY，支持交互式命令、Ctrl+C、vim 等；渲染由官方 xterm 组件负责。

## 悬浮球

- **单击**：展开/收起；展开时十字变 X，出现"工作区 / 刷新 / 设置"三个等大圆球。
- **拖动**：按住球自由移动（横竖皆可）；松手距离左右边缘 56 dp 内会吸附为半透明半球，只露出半边。
- **点击吸附态**：恢复完整球体。
- **点击穿透**：球与菜单之外的触摸会正常落到官方 UI，不影响聊天/文件操作。
- 位置和吸附侧会保存，旋转屏幕后仍在合法区域。

## 应用更新

设置页 → "检查更新"：

1. 读取 GitHub Releases 的 `update.json`；
2. 比较 `versionCode`，下载 APK；
3. 校验 SHA-256；
4. 通过 FileProvider 调起系统安装器（需要允许"安装未知应用"）。

更新不会删除应用私有数据（会话、配置、运行时按版本目录切换）；但请定期备份重要工作区。

## 引擎控制与日志

- 设置页：启动 / 停止 / 重启、当前工作区、同步状态（直连模式无需同步）、分享日志。
- 通知栏：引擎运行时显示常驻通知，可停止/重启，点击回到应用。
- 日志位置：`files/logs/engine.log`；通过设置页"分享日志"导出。调试时也可：

```bash
adb shell run-as io.github.luqijian.harnessandroid.debug tail -f files/logs/engine.log
```

## 数据位置

| 内容 | 路径 |
| --- | --- |
| 运行时 | `/data/user/0/<pkg>/files/runtime/<version>/rootfs` |
| dsh 配置/会话/凭据 | `/data/user/0/<pkg>/files/dsh-home`（guest `/root/.dsh`） |
| 日志 | `/data/user/0/<pkg>/files/logs/engine.log` |
| 临时文件 | `/data/user/0/<pkg>/files/tmp` |
| 工作区 | 你选择的原始文件夹（不被复制）；guest 内为 `/workspaces/default` |

卸载应用会删除以上私有目录；原始工作区文件夹不受影响。

## 备份与恢复

- 会话与配置：在终端把 `/root/.dsh`（即 `files/dsh-home`）打包备份；恢复时放回同一路径并重启引擎。
- 工作区：按普通文件备份即可；git 项目建议在宿主机或终端执行 `git bundle`。
- 运行时：无需备份，重新安装 APK 会自动解压。

## 限制

- 仅 arm64 Android 8.0+；WebView 需 Chrome 111+。
- 首次安装需要约 2.5 GB 可用空间（APK + 解压后的 rootfs）。
- 后台可能被 MIUI/HyperOS 等 ROM 冻结；请在电池设置中设为"无限制"并允许自启动。
- 官方桌面专属能力（Electron 托盘、桌面更新器、macOS Computer Use）不适用。
