"""Diagnosis orchestration, progress rendering, and public entry point."""

from __future__ import annotations

import time
from collections.abc import Callable, Sequence
from pathlib import Path

from rich.console import Console
from rich.table import Table

from hanppie.diagnosis.checks import DiagnosisChecks
from hanppie.diagnosis.discovery import discover_robots
from hanppie.diagnosis.model import (
    CHECK_BY_NAME,
    CHECKS,
    DiagnosisCheck,
    DiagnosisConfig,
    DiagnosisResult,
    validate_safety,
)
from hanppie.diagnosis.recorder import DiagnosisRecorder
from hanppie.diagnosis.session import DeviceSession
from hanppie.lab.protocol import RobotBroadcast
from hanppie.lab.robot import LabRobot


class DiagnosisRunner:
    def __init__(
        self,
        config: DiagnosisConfig,
        recorder: DiagnosisRecorder,
        *,
        console: Console | None = None,
        robot_factory: Callable[..., LabRobot] = LabRobot,
        sleep: Callable[[float], None] = time.sleep,
        discover: Callable[[float], list[RobotBroadcast]] = discover_robots,
        session_type: type[DeviceSession] = DeviceSession,
    ) -> None:
        self.config = config
        self.recorder = recorder
        self.console = console or Console()
        self.session = session_type(
            config,
            recorder,
            robot_factory=robot_factory,
            sleep=sleep,
            discover=discover,
        )
        self.handlers = DiagnosisChecks(self.session).handlers()

    def run(self) -> tuple[int, list[DiagnosisResult]]:
        results: list[DiagnosisResult] = []
        self.recorder.event("info", "diagnosis.start", "开始实机诊断", checks=self.config.checks)
        try:
            for name in self.config.checks:
                results.append(self._run_check(CHECK_BY_NAME[name]))
        finally:
            results.append(self._run_cleanup())
            self.recorder.write_report(self.config, results)
        self._print_summary(results)
        failed = any(result.status == "FAIL" for result in results)
        return (1 if failed else 0), results

    def _run_check(self, check: DiagnosisCheck) -> DiagnosisResult:
        started = time.monotonic()
        self.console.print(f"[bold cyan]→[/] {check.title} ({check.name})")
        self.recorder.event("info", "check.start", check.title, check=check.name, risk=check.risk)
        try:
            evidence = self.handlers[check.name]()
        except Exception as exc:
            duration = time.monotonic() - started
            result = DiagnosisResult(
                check.name,
                check.title,
                "FAIL",
                "执行失败",
                duration,
                error=f"{type(exc).__name__}: {exc}",
            )
            self.console.print(f"[bold red]FAIL[/] {check.title}: {exc}")
            self.recorder.event(
                "error",
                "check.finish",
                "执行失败",
                check=check.name,
                duration_seconds=duration,
                error=result.error,
            )
            return result
        duration = time.monotonic() - started
        summary = str(evidence.pop("_summary", "验证通过"))
        result = DiagnosisResult(check.name, check.title, "PASS", summary, duration, evidence)
        self.console.print(f"[bold green]PASS[/] {check.title}")
        self.recorder.event(
            "info",
            "check.finish",
            summary,
            check=check.name,
            duration_seconds=duration,
            evidence=evidence,
        )
        return result

    def _run_cleanup(self) -> DiagnosisResult:
        started = time.monotonic()
        self.console.print("[bold cyan]→[/] 安全清理 (cleanup)")
        evidence, errors = self.session.cleanup()
        duration = time.monotonic() - started
        status = "FAIL" if errors else "PASS"
        summary = "安全清理失败" if errors else "执行机构已归零，临时连接已关闭"
        result = DiagnosisResult(
            "cleanup",
            "安全清理",
            status,
            summary,
            duration,
            evidence,
            "; ".join(errors),
        )
        self.recorder.event(
            "error" if errors else "info",
            "diagnosis.cleanup",
            summary,
            duration_seconds=duration,
            evidence=evidence,
            errors=errors,
        )
        color = "red" if errors else "green"
        self.console.print(f"[bold {color}]{status}[/] 安全清理")
        return result

    def _print_summary(self, results: Sequence[DiagnosisResult]) -> None:
        table = Table(title="RoboMaster S1 实机诊断")
        table.add_column("项目")
        table.add_column("状态")
        table.add_column("耗时", justify="right")
        for result in results:
            style = "green" if result.status == "PASS" else "red"
            table.add_row(
                result.title,
                f"[{style}]{result.status}[/]",
                f"{result.duration_seconds:.2f}s",
            )
        self.console.print(table)
        self.console.print(
            f"报告：[link=file://{self.recorder.report_path}]{self.recorder.report_path}[/]"
        )
        self.console.print(
            f"日志：[link=file://{self.recorder.log_path}]{self.recorder.log_path}[/]"
        )


def render_check_catalog(console: Console) -> None:
    table = Table(title="可用实机诊断项目")
    table.add_column("名称")
    table.add_column("项目")
    table.add_column("风险")
    table.add_column("说明")
    for check in CHECKS:
        table.add_row(check.name, check.title, check.risk, check.description)
    console.print(table)


def run_diagnosis(
    config: DiagnosisConfig, *, console: Console | None = None
) -> tuple[int, Path, Path]:
    validate_safety(config)
    recorder = DiagnosisRecorder(config.output_base)
    runner = DiagnosisRunner(config, recorder, console=console)
    status, _ = runner.run()
    return status, recorder.report_path, recorder.log_path
