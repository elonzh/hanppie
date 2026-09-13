"""Long-lived isolated process that owns the physical robot connection."""

from __future__ import annotations

import os
import sys
from multiprocessing.connection import Connection
from pathlib import Path
from typing import Any

from hanppie.mcp.runtime import PersistentRuntime, RuntimeConfig


def run_worker(
    connection: Connection,
    config_values: dict[str, Any],
    log_path: str | None = None,
) -> None:
    """Serve private pipe requests until shutdown or parent disconnect."""

    sink = open(log_path or os.devnull, "a", encoding="utf-8", buffering=1)
    sys.stdout = sink
    sys.stderr = sink
    runtime = PersistentRuntime(RuntimeConfig(**config_values))
    try:
        while True:
            try:
                request = connection.recv()
            except EOFError:
                break
            action = request.get("action")
            try:
                if action == "status":
                    response = {"ok": True, "connection": runtime.status()}
                elif action == "connect":
                    response = {
                        "ok": True,
                        "connection": runtime.connect(request["robot_ip"], request["appid"]),
                    }
                elif action == "execute":
                    response = runtime.execute(
                        code=request["code"],
                        output_dir=Path(request["output_dir"]),
                        robot_access=str(request["robot_access"]),
                        robot_ip=request.get("robot_ip"),
                        appid=request.get("appid"),
                    )
                elif action == "disconnect":
                    errors = runtime.disconnect()
                    response = {
                        "ok": not errors,
                        "cleanup_errors": errors,
                        "connection": runtime.status(),
                    }
                elif action == "shutdown":
                    errors = runtime.disconnect()
                    connection.send({"ok": not errors, "cleanup_errors": errors})
                    break
                else:
                    raise ValueError(f"unknown worker action: {action!r}")
            except BaseException as exc:
                response = {
                    "ok": False,
                    "error": {"type": type(exc).__name__, "message": str(exc)},
                    "connection": runtime.status(),
                }
            connection.send(response)
    finally:
        runtime.disconnect()
        connection.close()
        sink.close()
