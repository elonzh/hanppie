# 参与贡献

Hanppie 会操作已经停止维护的实体机器人，其中部分恢复操作会临时暴露 root shell。所有贡献必须保持安全默认值、显式设备目标、哈希校验和可逆恢复路径。

## 开发环境

安装 [uv](https://docs.astral.sh/uv/) 和 [Task](https://taskfile.dev/)，然后运行：

```bash
task sync
task hooks
task check
```

`uv sync` 会安装内置的 `src/robomaster` SDK fork。仅在测试 App/Lab 后端时运行 `task sync:lab`；该任务使用独立的 `.venv-lab`，相关命令通过 `task run:lab -- ...` 执行，不要把 LAB-SDK 安装进主环境。

项目以 Python 3.10 为开发和测试基线，暂不维护更高版本的兼容矩阵。机内 Lab 载荷仍需保持固件解释器所要求的旧语法兼容性。

## 项目语言

- 中文是项目默认语言；面向用户的文档、Issue、PR 说明和提交信息使用中文。
- `README.md` 是中文入口，`README_EN.md` 是英文镜像；修改其中一份的事实性内容时同步另一份。
- 代码标识、协议字段、配置键和机器要求的值保留英文。
- 软硬件架构、协议、能力边界或工作原理发生变化时，同步更新 [`docs/architecture.md`](./docs/architecture.md)。

## Pull Request 要求

- 自动化测试中禁止真实机械动作。
- 单元测试必须模拟 ADB 和网络传输。
- 报告实机行为时记录准确固件版本。
- 明确区分“API 接受”“观察到遥测”“确认物理效果”三层证据。
- 不要提交设备备份、序列号相关日志、厂商二进制、密钥或局域网拓扑。
- 恢复的 `src/hanppie/runtime` 和 `resources` 只做必要的互操作性修改，不进行顺手重构。
- `src/robomaster` 来自 Apache-2.0 上游；修改时保留版权头、注明改动，并同步更新对应的离线或实机能力测试。

提交 PR 前运行 `task prek`。硬件相关 PR 应在说明中提供脱敏的实测证据；普通贡献不要求拥有 S1 真机。
