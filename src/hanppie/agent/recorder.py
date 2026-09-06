"""Lazy, session-scoped conversation records."""

from __future__ import annotations

import json
import threading
from collections.abc import Mapping
from datetime import datetime
from pathlib import Path
from typing import Any


class AgentRecorder:
    """Record only accepted wake-word conversation, never idle background speech."""

    def __init__(self, output_base: Path, session_id: str) -> None:
        self.session_dir = output_base.expanduser().resolve() / "sessions" / session_id
        self.events_path = self.session_dir / "events.jsonl"
        self._lock = threading.Lock()

    def record(self, event: str, data: Mapping[str, Any]) -> None:
        entry = {
            "timestamp": datetime.now().astimezone().isoformat(timespec="milliseconds"),
            "event": event,
            **data,
        }
        line = json.dumps(entry, ensure_ascii=False, default=str, separators=(",", ":"))
        with self._lock:
            self.session_dir.mkdir(parents=True, exist_ok=True)
            with self.events_path.open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")
