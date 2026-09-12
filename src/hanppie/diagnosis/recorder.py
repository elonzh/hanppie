"""Evidence recording for one diagnosis run."""

from __future__ import annotations

import json
import platform
from collections.abc import Sequence
from datetime import datetime
from pathlib import Path
from typing import Any

from hanppie import __version__
from hanppie.diagnosis.model import DiagnosisConfig, DiagnosisResult


class DiagnosisRecorder:
    """Write JSONL events and a human-readable report."""

    def __init__(self, output_base: Path, *, now: datetime | None = None) -> None:
        started = now or datetime.now().astimezone()
        self.started = started
        run_name = started.strftime("%Y%m%d-%H%M%S")
        base = output_base.expanduser().resolve()
        self.run_dir = base / run_name
        suffix = 1
        while self.run_dir.exists():
            self.run_dir = base / f"{run_name}-{suffix}"
            suffix += 1
        self.run_dir.mkdir(parents=True)
        self.log_path = self.run_dir / "events.jsonl"
        self.report_path = self.run_dir / "report.md"

    def event(self, level: str, event: str, message: str, **data: Any) -> None:
        entry = {
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "level": level,
            "event": event,
            "message": message,
            "data": data,
        }
        with self.log_path.open("a", encoding="utf-8") as stream:
            stream.write(json.dumps(entry, ensure_ascii=False, default=str) + "\n")

    def write_report(self, config: DiagnosisConfig, results: Sequence[DiagnosisResult]) -> None:
        ended = datetime.now().astimezone()
        lines = [
            "# RoboMaster S1 实机诊断报告",
            "",
            "> 本文件只保存本次诊断证据，不维护项目的长期技术结论。",
            "> Hanppie 当前实现与能力结论见 `docs/architecture.md`；RoboMaster 原生架构与协议结论见 `docs/architecture-robomaster.md`。",
            "",
            "## 执行元数据",
            "",
            f"- 开始时间：{self.started.isoformat(timespec='seconds')}",
            f"- 结束时间：{ended.isoformat(timespec='seconds')}",
            f"- Hanppie：{__version__}",
            f"- 主机 Python：{platform.python_version()}",
            f"- 主机平台：{platform.platform()}",
            f"- 诊断项目：{', '.join(config.checks)}",
            "",
            "## 结果索引",
            "",
            "| 项目 | 状态 | 耗时 | 摘要 |",
            "| --- | --- | ---: | --- |",
        ]
        for result in results:
            summary = result.summary.replace("|", "\\|").replace("\n", " ")
            lines.append(
                f"| {result.title} (`{result.name}`) | {result.status} | "
                f"{result.duration_seconds:.2f}s | {summary} |"
            )
        lines.extend(["", "## 分项证据", ""])
        for result in results:
            lines.extend(
                [
                    f"### {result.title} (`{result.name}`)",
                    "",
                    f"- 状态：{result.status}",
                    f"- 摘要：{result.summary}",
                ]
            )
            if result.error:
                lines.append(f"- 错误：{result.error}")
            if result.evidence:
                rendered = json.dumps(result.evidence, ensure_ascii=False, indent=2, default=str)
                lines.extend(["", "```json", rendered, "```"])
            lines.append("")
        lines.extend(
            [
                "## 原始事件日志",
                "",
                "同目录 `events.jsonl` 保存结构化逐步事件。",
                "",
            ]
        )
        self.report_path.write_text("\n".join(lines), encoding="utf-8")
