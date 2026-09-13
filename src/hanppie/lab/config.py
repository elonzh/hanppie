"""Configuration for Hanppie's RoboMaster App/Lab backend."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class LabConfig:
    control_port: int = 40923
    telemetry_port: int = 40924
    telemetry_period: float = 0.05
    command_timeout: float = 0.30
    bridge_ready_timeout: float = 5.0
    bridge_probe_interval: float = 0.10
    lab_mode_settle: float = 1.0
    upload_settle: float = 0.50
    program_start_settle: float = 1.0
    upload_retry_timeout: float = 5.0

    def __post_init__(self) -> None:
        for name in ("control_port", "telemetry_port"):
            if not 1 <= int(getattr(self, name)) <= 65535:
                raise ValueError(f"{name} must be in 1..65535")
        if self.telemetry_period <= 0:
            raise ValueError("telemetry_period must be positive")
        for name in (
            "command_timeout",
            "bridge_ready_timeout",
            "bridge_probe_interval",
            "lab_mode_settle",
            "upload_settle",
            "program_start_settle",
            "upload_retry_timeout",
        ):
            if float(getattr(self, name)) < 0:
                raise ValueError(f"{name} must not be negative")


DEFAULT_CONFIG = LabConfig()
