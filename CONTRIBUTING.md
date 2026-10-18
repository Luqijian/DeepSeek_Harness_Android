# 贡献指南

感谢你帮助 Harness Android 变得更好。提交 Issue 或 PR 前请先阅读以下约定。

## 适合提交的内容

- Android 端运行时生命周期、WebView、文件访问、悬浮球、更新器等原生问题。
- 与官方 `@deepseek-ai/dsh` 新版本的兼容性问题（附 `dsh --version`、Android 版本、日志）。
- 构建脚本、CI、文档、可复现性改进。
- 平板/手机的兼容性报告（请注明型号、Android 版本、WebView 版本、是否 16 KB 页）。

## 开发流程

```bash
# 运行时（Linux arm64 主机或 CI）
bash runtime/build-runtime.sh

# 放到 App 中
cp runtime/out/rootfs.tar.gz app/src/main/assets/runtime/rootfs.bin
cp runtime/out/manifest.json app/src/main/assets/runtime/manifest.json
cp runtime/out/jniLibs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/

# 构建与安装
./gradlew :app:assembleDebug
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
```

- 需要 JDK 17、Android SDK 36、Python 3（构建脚本）。
- 只修改 Android 配置层时不会动上游源码；若必须打补丁，请在 PR 中说明原因、补丁文件与失效条件。
- 不要在 PR 中提交：`app/src/main/assets/runtime/rootfs.bin`、`jniLibs/*.so` 构建产物、`local.properties`、任何 keystore、Token 或 API Key。
- 提交前至少运行一次 `./gradlew :app:assembleDebug`，并在真机/模拟器上验证改动路径。

## 提交 PR

1. Fork 并从 `main` 开分支。
2. 描述问题、根因、修改点与测试设备/步骤；UI 改动请附截图。
3. 保持提交信息清晰；一个 PR 聚焦一个主题。
4. CI 必须通过；16 KB ELF 校验失败时请检查 NDK/预编译包来源。

## 文档

- 中文 README 是主文档，English 版本保持同步（`README.en.md`）。
- 构建、架构、使用、排障变化时同步更新 `docs/` 下对应文件。
- 安全相关问题请勿公开提交细节，按 [SECURITY.md](SECURITY.md) 私下报告。

## 许可

贡献的代码默认以本仓库的 MIT 许可发布。第三方组件保持其原始许可，详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
