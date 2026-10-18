# 测试指南

## 自动化验收（已连接设备 + USB 调试）

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
PKG=io.github.luqijian.harnessandroid.debug
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
```

Debug 构建内置自动化入口（验证直连工作区）：

```bash
# 直接把某个共享存储目录激活为工作区
adb shell am start -n $PKG/io.github.luqijian.harnessandroid.debug.ActivateWorkspaceActivity \
  --es path /storage/emulated/0/Download/TestProject
```

> `GrantTreeUriActivity`（SAF tree URI 导入）仅作为镜像/同步实验代码的调试入口保留；Developer Edition 的 UI 只提供直连模式。

## 验收清单

1. **首次启动**：安全说明 → 运行时解压（显示 `N% · 正在解压运行时（文件数）`）→ 自动进入官方 UI。
2. **直连工作区**：悬浮球 → 工作区 → 选择 `/sdcard/Download/TestProject`；引擎重启后文件面板可见 `.git`、`src`、`README.md`，README 预览正常。
3. **终端**：右侧边栏 → 新建终端，执行 `pwd`（`/workspaces/default`）、`node src/index.js`、`git status --short`、`echo TERMINAL_OK`。
4. **账号登录**：官方 UI → 登录，确认登录弹窗正常渲染（不是 `about:blank`），可取消返回。
5. **悬浮球**：十字点击展开/X 收缩、三项菜单可点；横向与纵向拖动均有效且球完整；拖到左右边缘吸附为半透明半球，点击恢复；点击球外区域不遮挡 WebView。
6. **后台**：息屏 10 分钟后通知仍在，唤醒后 UI 可用（激进 ROM 需设置电池无限制）。
7. **设置页**：启动/停止/重启、当前工作区、分享日志、检查更新。
8. **更新链路**：Release 含 APK + `SHA256SUMS` + `update.json`；应用内检查能识别新版本并调起安装器。

## 日志与观察点

```bash
adb shell run-as $PKG tail -f files/logs/engine.log
```

关键 tag：

- `runtime`：解压进度、SHA-256、安装位置；
- `config`：受管 `cordis.patch.yml`、默认工作区符号链接；
- `launch`：launcher 路径；
- `engine`：启动、ready 端口、进程退出/清理；
- `dsh`：官方引擎 stdout；
- `webview`：页面加载、popup 请求；
- `update`：更新检查/下载/校验。

## CI 验证

- `build.yml`：
  - `ubuntu-24.04-arm` 构建运行时；
  - 校验所有 `jniLibs/*.so` 的 16 KB 对齐；
  - 构建 debug APK；`v*` tag 构建 release APK；
  - tag 发布 GitHub Release（APK、SHA256SUMS、update.json）。
- `upstream-sync.yml`：每日对比 npm `dist-tags.latest`，升级 `gradle.properties` 的 `dshVersion`，跑一次运行时冒烟构建，通过后自动开 PR。

## 手动回归矩阵

| 场景 | 预期 |
| --- | --- |
| 全新安装（无应用数据） | 引导 → 解压 → UI，无崩溃 |
| 覆盖安装新 APK | 会话/配置/工作区保留，运行时按版本切换 |
| 切换工作区 A → B → A | 每次重启后文件面板与终端 `pwd` 指向正确目录 |
| 引擎运行中强停应用再打开 | 旧 proot 进程组被清理，新引擎正常监听 3080 |
| 无可用空间 | 首次解压前给出空间不足错误，不留下半成品运行时 |
| WebView < 111 | 给出升级提示，不进入黑屏 |
| 登录弹窗关闭 | 主页面仍可用，不变成 `about:blank` |
