# AGENTS.md

- 架构、协议、能力边界或工作原理变化时，必须同步更新 `docs/architecture.md`。
- 不随意重构 `src/hanppie/runtime` 或 `resources` 中的恢复内容，只做有证据的必要修改。
- 实机操作必须显式指定目标，默认不执行机械运动；提交前运行 `task check` 和 `task prek`。
- 不提交设备备份、序列号、凭据、厂商二进制或未脱敏日志。
