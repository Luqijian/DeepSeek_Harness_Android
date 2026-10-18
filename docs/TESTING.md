# 测试指南

## 自动化验收（已连接设备 + USB 调试）

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
PKG=io.github.luqijian.harnessandroid.debug
adb shell pm grant $PKG android.permission.POST_NOTIFICATIONS
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
```

Debug 构建内置两个自动化入口：

```bash
# 直接把某个共享存储目录激活为工作区（Dev 直连模式）
adb shell am start -n $PKG/io.github.luqijian.harnessandroid.debug.ActivateWorkspaceActivity \
  --es path /storage/emulated/0/Download/TestProject

# （Play/镜像模式备用）通过持久化 tree URI 导入
adb shell am start -n $PKG/io.github.luqijian.harnessandroid.debug.GrantTreeUriActivity \
  -a android.intent.action.VIEW \
  -d 'content://com.android.externalstorage.documents/tree/primary%3ADownload%2FTestProject' \
  --grant-read-uri-permission --grant-write-uri-permission \
  --grant-persistable-uri-permission --grant-prefix-uri-permission
```

## 验收清单

1. 首次启动：安全说明 → 解压运行时（~40s，51702 文件）→ WebView 自动加载官方 UI。
2. 直连工作区：工具栏“工作区”选择 `/sdcard/Download/TestProject`，引擎重启后文件面板可见 `.git`、`src`、`README.md`。
3. 终端：右侧边栏 → 新建终端，执行 `pwd`（应为 `/workspaces/default` 或工作区路径）、`node src/index.js`、`git status`。
4. 引擎日志：`adb shell run-as $PKG tail -f files/logs/engine.log`（tag：runtime/config/launch/engine/dsh）。
5. 息屏存活：锁屏 10 分钟后通知栏仍显示“运行中”，唤醒后 UI 可用。
6. 设置页：引擎启动/停止/重启、当前工作区、分享日志、检查更新。

## CI 验证

- `build` workflow：运行时构建（arm64 runner）→ 16 KB ELF 对齐校验 → debug/release APK → tag 时发布 Release + `update.json`。
- `upstream-sync` workflow：每日对比 npm `dist-tags.latest`，升级 `gradle.properties` 的 `dshVersion` 并跑一次运行时冒烟构建，通过后自动开 PR。
