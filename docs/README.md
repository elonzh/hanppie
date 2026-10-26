# 文档导航

了解软件怎么用，从 [安装与使用](../README.md#安装) 开始；核对现在实现了什么，先看 [当前能力矩阵](architecture/hanppie.md#110-当前能力矩阵)。历史复核只描述对应日期和提交，不作为当前功能清单。

## 按目的阅读

| 你想做什么 | 从这里开始 | 继续阅读 |
| --- | --- | --- |
| 安装、连接和使用机器人 | [安装与使用](../README.md) | 应用内“使用指南” |
| 运行源码、构建或修改代码 | [开发指南](guides/development.md) | [工程组织与模块划分](architecture/hanppie.md#131-多平台工程组织与模块划分) |
| 理解当前实现、状态归属与安全边界 | [Hanppie 技术架构](architecture/hanppie.md) | [安全与恢复模型](architecture/hanppie.md#112-安全与恢复模型) |
| 设计界面或对话功能 | [产品设计规范](../DESIGN.md) | [对话体验](product/conversation.md)、[智能体产品设计](product/agent.md) |
| 理解智能体的存储、执行与恢复契约 | [智能体运行时设计](architecture/agent-runtime.md) | [当前智能体实现](architecture/hanppie.md#137-智能体运行时架构) |
| 查原机机制、协议或官方 App 差异 | [RoboMaster 架构与协议](architecture/robomaster.md) | [官方 App 对照资料](reference/official-app/README.md) |
| 排查回归、核对测试或追溯结论 | [验证与历史记录](records/README.md) | 按主题、日期和提交定位原始记录 |

## 目录与维护

| 位置 | 内容 |
| --- | --- |
| [guides](guides/development.md) | 源码运行、构建、测试等操作指南 |
| [product](product/conversation.md) | 对话体验与智能体产品规则；通用产品规范在根目录 [DESIGN.md](../DESIGN.md) |
| [architecture](architecture/hanppie.md) | Hanppie 当前实现、RoboMaster 原生机制、运行时设计契约 |
| [reference/official-app](reference/official-app/README.md) | 能力对照台账、固定版官方 App 的配置、桥接 key 与静态调查依据 |
| [records](records/README.md) | 实机、界面、发布和官方 App 复核的日期化证据 |

新增内容按职责放入相应目录，并更新对应索引。当前事实在所属长期文档中维护，单次测试、开发过程和已废弃方案进入记录或 Git 历史；详细约定见 [AGENTS.md](../AGENTS.md#文档边界)。
