"""RoboMaster communication and control library."""

from __future__ import annotations

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

__version__ = "0.1.0"
