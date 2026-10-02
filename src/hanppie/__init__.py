"""RoboMaster communication and control library."""

from __future__ import annotations

from ._version import __version__
from .connection import AppConnection, AppConnectionInfo
from .lab import LabProgramIdentity, build_lab_program, upload_lab_program
from .product import RobotCapabilities, RobotComponent, RobotModel, RobotProduct
from .robot import GimbalTelemetry, Odometry, Robot, RobotAck

__all__ = [
    "AppConnection",
    "AppConnectionInfo",
    "GimbalTelemetry",
    "LabProgramIdentity",
    "Odometry",
    "Robot",
    "RobotAck",
    "RobotCapabilities",
    "RobotComponent",
    "RobotModel",
    "RobotProduct",
    "__version__",
    "build_lab_program",
    "upload_lab_program",
]
