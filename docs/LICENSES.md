# 开源许可与第三方组件

本仓库自身代码以 MIT 许可发布。APK 内置并分发以下第三方组件，各自遵循其原始许可：

| 组件 | 来源 | 许可 | 用途 |
|---|---|---|---|
| DeepSeek Harness (`@deepseek-ai/dsh` 及其依赖包) | https://github.com/deepseek-ai/deepseek-harness | MIT | Agent 引擎与官方 Web UI（npm 安装，未修改源码） |
| Node.js | https://nodejs.org （官方 linux-arm64 二进制） | MIT | JavaScript 运行时 |
| Debian 13 rootfs | https://www.debian.org （Docker 官方镜像层） | Debian 社会契约 / 各包许可 | Linux 用户态（bash、git、ripgrep、g++ 等） |
| proot / loader | https://github.com/termux/proot （Termux 软件包） | GPL-2.0 | 用户态 chroot/bind（宿主侧二进制） |
| libtalloc | https://talloc.samba.org （Termux 软件包） | LGPL-3.0+ | proot 依赖 |
| libandroid-shmem | https://github.com/termux/libandroid-shmem | Apache-2.0 / BSD-3-Clause | proot 依赖 |

proot 为 GPL-2.0，本仓库以独立二进制形式随 APK 分发；如需其源码，请访问上方上游仓库。Debian 内组件的完整许可清单可在运行时于 guest 内执行 `/usr/share/doc/*/copyright` 获取。

## 说明

- 官方 dsh 以 npm 原样安装；本项目的适配仅通过配置层（`cordis.patch.yml` 受管块）与运行环境变量完成，不改引擎源码。
- 如需移除任何组件，可参考 `runtime/build-runtime.sh` 自行裁剪后构建。
