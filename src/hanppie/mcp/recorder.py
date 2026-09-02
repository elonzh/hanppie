"""Session-scoped MCP logs and structured tool-call records."""

from __future__ import annotations

import json
import platform
import threading
import time
import traceback
import uuid
from collections.abc import Callable, Mapping
from datetime import datetime
from pathlib import Path
from typing import Any, TypeVar

from hanppie import __version__

Result = TypeVar("Result")


class MCPRecorder:
    """Keep one MCP server session's logs below its configured data root."""

    def __init__(self, output_base: Path, *, now: datetime | None = None) -> None:
        started = now or datetime.now().astimezone()
        self.started = started
        self.session_id = f"{started.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}"
        self.output_base = output_base.expanduser().resolve()
        self.session_dir = self.output_base / "sessions" / self.session_id
        self.calls_dir = self.session_dir / "calls"
        self.server_log_path = self.session_dir / "server.log"
        self.calls_log_path = self.session_dir / "calls.jsonl"
        self._lock = threading.RLock()
        self._active = False
        self._closed = False
        self._started_monotonic = time.monotonic()

    def record_call(
        self,
        tool: str,
        arguments: Mapping[str, object],
        operation: Callable[[], Result],
    ) -> Result:
        """Run one tool operation and append its complete input and outcome."""

        call_id = uuid.uuid4().hex
        started_at = datetime.now().astimezone()
        started = time.monotonic()
        self._write_call(
            {
                "timestamp": started_at.isoformat(timespec="milliseconds"),
                "session_id": self.session_id,
                "call_id": call_id,
                "event": "started",
                "tool": tool,
                "arguments": dict(arguments),
            }
        )
        try:
            result = operation()
        except BaseException as exc:
            duration = round(time.monotonic() - started, 6)
            self._write_call(
                {
                    "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
                    "session_id": self.session_id,
                    "call_id": call_id,
                    "event": "completed",
                    "tool": tool,
                    "status": "error",
                    "duration_seconds": duration,
                    "error": {
                        "type": type(exc).__name__,
                        "message": str(exc),
                        "traceback": traceback.format_exc(),
                    },
                }
            )
            self.log(
                "ERROR",
                "tool.call",
                call_id=call_id,
                tool=tool,
                status="error",
                duration_seconds=duration,
                error_type=type(exc).__name__,
                error_message=str(exc),
            )
            raise
        duration = round(time.monotonic() - started, 6)
        status = "error" if isinstance(result, dict) and result.get("ok") is False else "ok"
        self._write_call(
            {
                "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
                "session_id": self.session_id,
                "call_id": call_id,
                "event": "completed",
                "tool": tool,
                "status": status,
                "duration_seconds": duration,
                "result": result,
            }
        )
        self.log(
            "INFO" if status == "ok" else "ERROR",
            "tool.call",
            call_id=call_id,
            tool=tool,
            status=status,
            duration_seconds=duration,
        )
        return result

    def log(self, level: str, event: str, **data: object) -> None:
        """Append one operational line without writing to MCP STDIO."""

        with self._lock:
            self._ensure_active()
            self._write_log(level, event, data)

    def close(self) -> None:
        """Record the normal end of this server session once."""

        with self._lock:
            if self._closed:
                return
            self._closed = True
            if not self._active:
                return
            self._write_log(
                "INFO",
                "session.stop",
                {"duration_seconds": round(time.monotonic() - self._started_monotonic, 6)},
            )

    def _write_call(self, entry: Mapping[str, Any]) -> None:
        line = json.dumps(entry, ensure_ascii=False, default=str, separators=(",", ":"))
        with self._lock:
            self._ensure_active()
            with self.calls_log_path.open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")

    def _ensure_active(self) -> None:
        """Create the session tree only when the first real tool call is recorded."""

        if self._active:
            return
        self.calls_dir.mkdir(parents=True, exist_ok=False)
        self._active = True
        self._write_log(
            "INFO",
            "session.start",
            {
                "version": __version__,
                "python": platform.python_version(),
                "platform": platform.platform(),
            },
        )

    def _write_log(self, level: str, event: str, data: Mapping[str, object]) -> None:
        timestamp = datetime.now().astimezone().isoformat(timespec="milliseconds")
        details = json.dumps(data, ensure_ascii=False, default=str, separators=(",", ":"))
        line = f"{timestamp} {level.upper()} {event} {details}\n"
        with self.server_log_path.open("a", encoding="utf-8") as stream:
            stream.write(line)
