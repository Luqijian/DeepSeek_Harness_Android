# 文档索引

Harness Android 的文档按"先跑起来，再理解，再排障"组织。主入口是仓库根的 [README](../README.md) / [README.en.md](../README.en.md)。

| 文档 | 适合谁 | 内容 |
| --- | --- | --- |
| [BUILD.md](BUILD.md) | 构建者 / 维护者 | 运行时与 APK 构建、签名、CI 发布、上游同步 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 想理解实现的人 | 启动链路、proot 挂载、配置层、生命周期、安全边界 |
| [USAGE.md](USAGE.md) | 使用者 | 安装、权限、工作区、终端、账号/API Key、更新、数据位置 |
| [TESTING.md](TESTING.md) | 测试 / 贡献者 | adb 自动化入口、平板验收清单、CI 验证 |
| [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | 遇到问题的人 | 黑屏、后台冻结、git 所有权、端口、空间等 |
| [PRIVACY.md](PRIVACY.md) | 所有人 | 本地优先的数据与隐私说明 |
| [../THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) | 合规 / 分发 | Debian、Node、proot、dsh 等第三方许可 |
| [../CHANGELOG.md](../CHANGELOG.md) | 所有人 | 版本变更记录 |
| [../SECURITY.md](../SECURITY.md) | 安全研究者 | 威胁模型与漏洞报告方式 |

## 约定

- 中文文档是主版本；涉及用户可见行为时同步更新 English README。
- 命令默认在仓库根目录执行；`$PKG` 指代调试包名 `io.github.luqijian.harnessandroid.debug`。
- 运行时与 APK 的大文件不入库，由 `runtime/build-runtime.sh` 和 CI 生成。
