# AGENTS.md

- 架构、协议、能力边界或工作原理变化时，必须同步更新 `docs/architecture.md`。
- 不随意重构 `src/hanppie/runtime` 或 `resources` 中的恢复内容，只做有证据的必要修改。
- `src/robomaster` 是 Apache-2.0 上游 fork；保留官方导入接口、版权头和来源记录，只做有测试或实机证据的 S1 适配。
- 主机代码与 CI 以 Python 3.10 为基线；机内 Lab 载荷的解释器兼容性单独维护。
- 实机操作必须显式指定目标，默认不执行机械运动；提交前运行 `task check` 和 `task prek`。
- 不提交设备备份、序列号、凭据、厂商二进制或未脱敏日志。
