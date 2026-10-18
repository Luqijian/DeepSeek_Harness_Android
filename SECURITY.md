# 安全策略

## 报告漏洞

请不要在公开 Issue 中披露可利用细节。请通过 GitHub Security Advisories（仓库 → Security → Report a vulnerability）私下报告，或联系仓库维护者。请包含：

- 受影响的版本 / commit；
- 设备与 Android 版本；
- 复现步骤与影响范围；
- 如有可能，提供最小化 PoC（不要包含真实 API Key）。

我们会在确认后尽快回复并协调修复与披露时间。

## 威胁模型

Harness Android 是一个**本地开发者工具**，不是多租户 sandbox：

- Agent 可以执行任意 shell 命令，并以应用 UID 读写你授权的工作区。
- 官方 Landlock/bwrap 在 Android 上不可用，运行时使用 `danger-full-access`；proot 只是用户态路径/系统调用转换，**不是安全边界**。Android 应用沙箱是唯一隔离层。
- 应用申请“所有文件访问权限”用于直接打开工作区；不会主动扫描未打开的目录。
- Web UI 只监听 `127.0.0.1`，使用启动 token 与签名 Cookie；不默认开放局域网。
- API Key 保存在本地 `$DSH_HOME/.credentials.yaml`；应用没有遥测或统计 SDK。
- 模型请求会把你选择的内容发送给你配置的模型服务商；请避免在对话中放入敏感数据。

## 加固建议

- 只打开可信项目，定期备份重要文件。
- 为 API Key 设置用量上限，必要时使用独立 Key。
- 不要把设备借给不受信任的人；卸载应用会删除本地会话与配置。
- 不要通过第三方修改版安装包更新；只使用本仓库 Releases 的 APK。
