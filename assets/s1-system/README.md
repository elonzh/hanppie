# RoboMaster S1 机载系统文件备份与参考副本

本目录按 DJI RoboMaster S1 机器人机内系统（固件版本 `00.06.0521`）的**原始绝对路径**归档提取的代码、启动脚本与配置文件。

## 目录结构与原始路径映射

| 仓库相对路径 | 机内原始绝对路径 | 作用与说明 |
| --- | --- | --- |
| `data/dji/dji_scratch.py` | `/data/dji/dji_scratch.py` | Lab/Scratch 原厂常驻管理进程，负责程序接收、生命周期和日志转发 |
| `data/dji_scratch/src/robomaster/` | `/data/dji_scratch/src/robomaster/` | 机载 Python 3.6 控制栈与 DUSS 模块库（`rm_ctrl.py`、`rm_module.py`、`event_client.py`、`duml_cmdset.py`、`custom_ui/`、`multi_comm/` 等） |
| `system/bin/adb_en.sh` | `/system/bin/adb_en.sh` | 原厂 ADB 调试使能脚本（配置 USB/TCP 调试属性） |
| `system/bin/start_dji_system.sh` | `/system/bin/start_dji_system.sh` | 核心服务启动脚本（网络模式判断、FTP 启动、音频驱动、服务属性配置等） |
| `system/etc/dji.json` | `/system/etc/dji.json` | 平台、HAL 与 DUSS 消息路由表配置 |
| `root/init.rc` | `/init.rc` | 根文件系统 init 配置，声明全局环境变量、分区挂载与服务定义（如 `service dji_scratch`） |
| `root/default.prop` | `/default.prop` | 系统默认属性配置 |
| `root/init.lc1860.3connective.rc` | `/init.lc1860.3connective.rc` | 联芯 LC1860 芯片级连接与外设 init 配置 |
| `root/init.usb.rc` | `/init.usb.rc` | USB 复合设备类属性与切换逻辑 |
