"""Data model and safety policy for RoboMaster S1 diagnosis."""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any


@dataclass(frozen=True)
class DiagnosisCheck:
    name: str
    title: str
    description: str
    risk: str = "read-only"


CHECKS = (
    DiagnosisCheck("discovery", "设备发现", "监听并解析 S1 App 广播"),
    DiagnosisCheck("app", "App 会话", "建立 App 会话并检查基础状态"),
    DiagnosisCheck("video", "相机视频", "拉取并解码一帧 720p H.264 视频"),
    DiagnosisCheck("lab", "Lab 执行", "上传临时程序并验证双向遥测"),
    DiagnosisCheck("led", "装甲灯", "依次点亮红、绿、蓝、白并关闭", "visual"),
    DiagnosisCheck("speaker", "扬声器", "播放一次内置提示音", "auditory"),
    DiagnosisCheck(
        "chassis",
        "底盘运动",
        "依次测试前、后、左、右、逆时针和顺时针运动",
        "motion",
    ),
    DiagnosisCheck(
        "gimbal",
        "云台运动",
        "依次测试俯仰正负方向、偏航正负方向并回中",
        "motion",
    ),
    DiagnosisCheck("infrared", "红外发射", "发射一次红外信号", "infrared"),
    DiagnosisCheck("gel", "水弹发射", "空弹仓触发一次水弹发射", "gel"),
    DiagnosisCheck("adb", "临时 ADB", "通过 Lab 临时启用并验证 root ADB", "root"),
    DiagnosisCheck("system", "机内信息", "采集系统、服务、进程、端口和文件证据", "root"),
)
CHECK_BY_NAME = {check.name: check for check in CHECKS}
DEFAULT_CHECKS = (
    "discovery",
    "app",
    "video",
    "lab",
    "led",
    "speaker",
    "adb",
    "system",
)


@dataclass(frozen=True)
class DiagnosisConfig:
    checks: tuple[str, ...]
    output_base: Path
    robot_ip: str | None = None
    appid: str | None = None
    timeout: float = 10.0
    discovery_timeout: float = 4.0
    allow_motion: bool = False
    allow_infrared: bool = False
    allow_gel: bool = False
    adb: str = "adb"
    debug: bool = False


@dataclass
class DiagnosisResult:
    name: str
    title: str
    status: str
    summary: str
    duration_seconds: float
    evidence: dict[str, Any] = field(default_factory=dict)
    error: str = ""


def normalize_check_names(values: Iterable[str], *, all_checks: bool = False) -> tuple[str, ...]:
    """Normalize repeated or comma-separated check names into execution order."""

    requested: set[str] = set()
    for value in values:
        requested.update(part.strip().lower() for part in value.split(",") if part.strip())
    if all_checks or "all" in requested:
        requested = set(CHECK_BY_NAME)
    unknown = requested - CHECK_BY_NAME.keys()
    if unknown:
        names = ", ".join(sorted(unknown))
        raise ValueError(f"未知诊断项目：{names}")
    return tuple(check.name for check in CHECKS if check.name in requested)


def validate_safety(config: DiagnosisConfig) -> None:
    """Reject hazardous checks unless each independent safety gate is open."""

    selected = set(config.checks)
    errors: list[str] = []
    if selected.intersection({"chassis", "gimbal"}) and not config.allow_motion:
        errors.append("底盘/云台诊断需要 --allow-motion")
    if "infrared" in selected and not config.allow_infrared:
        errors.append("红外诊断需要 --allow-infrared")
    if "gel" in selected and not config.allow_gel:
        errors.append("水弹诊断需要 --allow-gel，并确认弹仓为空且射界安全")
    if errors:
        raise ValueError("；".join(errors))
