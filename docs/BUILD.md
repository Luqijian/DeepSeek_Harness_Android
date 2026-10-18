# 构建指南

本文覆盖三件事：构建内置 Linux 运行时、构建 APK、以及 CI 如何自动跟随上游并发布。

## 1. 环境要求

| 用途 | 要求 |
| --- | --- |
| 运行时构建 | Linux **arm64**（GitHub Actions 使用 `ubuntu-24.04-arm`），root 或可 `sudo`，Python 3，`curl`、`xz`、`tar`、`gzip` |
| APK 构建 | JDK 17、Android SDK Platform 36、Build-Tools 36.0.0、Gradle Wrapper |
| 签名 | 一个 upload keystore（本地调试可省略，release 会退回 debug 签名） |
| 真机测试 | adb + arm64 Android 8.0+ |

> 运行时脚本使用 `chroot` 安装 Debian 包。x86_64 主机需要自行准备 qemu binfmt；最省事的方式是直接使用 `ubuntu-24.04-arm` CI 或一台 arm64 Linux 机器。

## 2. 构建运行时

```bash
bash runtime/build-runtime.sh
```

脚本按顺序完成：

1. 从 Docker Hub 拉取 `debian:trixie` 的 arm64 镜像层，合并成 rootfs tar；
2. 把 hardlink 转成普通文件副本（Android SELinux 在很多目录拒绝 `link(2)`）；
3. 解压 rootfs，安装官方 Node 24.22.0 linux-arm64 到 `/usr/local`；
4. 在 chroot 内 `apt-get` 安装 bash、git、ripgrep、python3、build-essential、openssh-client 等，并 `npm install -g @deepseek-ai/dsh@<dshVersion>`、`pnpm`；
5. 把多份嵌套的 `zod@4.4.3` 去重为一份 + 符号链接（约省 315 MB）；
6. 清理 npm/apt/doc/locale 缓存，打包为 `runtime/out/rootfs.tar.gz`；
7. 从 Termux 仓库取 proot/loader/libtalloc/libandroid-shmem，把 proot 的 `DT_NEEDED` 中 `libtalloc.so.2` 改写为 `libtalloc.so`，输出到 `runtime/out/jniLibs/arm64-v8a/`；
8. 生成 `runtime/out/manifest.json`（版本、dshVersion、SHA-256、大小）。

`dshVersion` 默认读取 `gradle.properties`；临时指定：

```bash
DSH_VERSION=0.2.1-rc.2 bash runtime/build-runtime.sh
```

### 产物

```text
runtime/out/
├── rootfs.tar.gz                 # 压缩后的 Debian 根文件系统 + Node + dsh
├── manifest.json                 # 安装校验与进度分母
└── jniLibs/arm64-v8a/
    ├── libproot.so               # proot 可执行文件（DT_NEEDED 已适配 Android）
    ├── libproot_loader.so        # proot loader
    ├── libtalloc.so
    └── libandroid-shmem.so
```

复制进 App：

```bash
mkdir -p app/src/main/assets/runtime app/src/main/jniLibs/arm64-v8a
cp runtime/out/rootfs.tar.gz app/src/main/assets/runtime/rootfs.bin
cp runtime/out/manifest.json app/src/main/assets/runtime/manifest.json
cp runtime/out/jniLibs/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

> 资产命名为 `.bin` 是刻意的：AGP 会把 `.tar.gz` 自动解压成 `.tar` 再打包，APK 会从 ~349 MB 膨胀到 1.2 GB。不要改回 `.tar.gz`，也不要为它配置 `noCompress`。

## 3. 构建 APK

`local.properties` 指向 SDK：

```properties
sdk.dir=/path/to/android-sdk
```

```bash
./gradlew :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Release（需要 keystore，环境变量或 `local.properties` 均可）：

```bash
export KEYSTORE_PATH=/secure/release.keystore
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=...
export KEY_PASSWORD=...
export VERSION_CODE=1024          # 单调递增
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名规则：

- Release 未配置 keystore 时退回 debug 签名，仅用于本地测试；
- 正式分发必须保证 **同一个 keystore**，否则应用内更新会因签名不一致被系统拒绝；
- CI 从 Secrets 读取 `KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`，解码到临时路径后构建。

版本号：`versionName = <appVersionName>+dsh<dshVersion>`，`versionCode` 由 CI 的 `GITHUB_RUN_NUMBER` 提供。

## 4. CI 与自动发布

### `build.yml`

- `runtime` job（`ubuntu-24.04-arm`）：执行运行时构建，校验所有 `jniLibs/*.so` 的 `LOAD` 段 `p_align ≥ 0x4000`（16 KB）；
- `apk` job：下载运行时产物，放入 App，构建 debug APK；`v*` tag 时构建 release APK；
- 打 tag 后创建 GitHub Release，附带 APK、`SHA256SUMS`、`update.json`（供应用内更新）；
- 所有分支/PR 都会构建，便于验证。

### `upstream-sync.yml`

- 每日 21:00 UTC（北京时间 05:00）或手动触发；
- 读取 npm `dist-tags`（默认 `latest`，可切换 `next`/`alpha`），与 `gradle.properties` 的 `dshVersion` 比较；
- 有新版本时先跑一次运行时构建做冒烟验证，通过后创建 `upstream/dsh-<version>` 分支与 PR；
- 合并 PR → `build.yml` 发布新 APK → 应用内更新可用。

## 5. 构建后验证

```bash
# 16 KB 对齐（CI 已做，可本地复查）
python3 - <<'PY'
import glob, struct, sys
for path in glob.glob('runtime/out/jniLibs/arm64-v8a/*.so'):
    data = open(path, 'rb').read()
    e_phoff = struct.unpack_from('<Q', data, 0x20)[0]
    e_phentsize = struct.unpack_from('<H', data, 0x36)[0]
    e_phnum = struct.unpack_from('<H', data, 0x38)[0]
    for i in range(e_phnum):
        off = e_phoff + i * e_phentsize
        p_type = struct.unpack_from('<I', data, off)[0]
        p_align = struct.unpack_from('<Q', data, off + 48)[0]
        if p_type == 1 and p_align < 0x4000:
            sys.exit(f'{path}: LOAD align {p_align:#x} < 16 KB')
print('all ELF aligned')
PY

# APK 安装与首启
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.luqijian.harnessandroid.debug/io.github.luqijian.harnessandroid.MainActivity
```

首次启动解压约 30–60 秒；日志：

```bash
adb shell run-as io.github.luqijian.harnessandroid.debug tail -f files/logs/engine.log
```

## 6. 常见构建问题

| 现象 | 处理 |
| --- | --- |
| `chroot: failed to run command` | 主机不是 arm64，或用非 root 用户运行；改用 arm64 runner / `sudo` |
| 运行时下载失败 | 检查 Docker Hub / nodejs.org / Termux 仓库网络；重试脚本 |
| `rootfs.bin` 导致 APK 1.2 GB | 资产必须命名为 `.bin`，不要使用 `.tar.gz` |
| Release 安装被拒 | 前后签名不一致；改用同一 keystore 或先卸载 |
| 16 KB 校验失败 | 预编译 `.so` 来源不满足对齐；改用 Termux NDK r28+ 构建或更新包 |
| `gradle` 找不到 SDK | 配 `local.properties` 或 `ANDROID_HOME` |
