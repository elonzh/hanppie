"""Agent-facing tools backed by the persistent Hanppie Python executor."""

from __future__ import annotations

import time
from pathlib import Path
from typing import Any

from hanppie.agent.model import ModelGateway, ToolCall, ToolResult
from hanppie.agent.policy import RobotCodePolicy
from hanppie.mcp.executor import PythonExecutor

_OBSERVATION_CODE = """robot.camera.start_video_stream(resolution="720p")
try:
    frame = robot.camera.read_video_frame(timeout=3.0, strategy="newest")
    if frame is None:
        raise RuntimeError("S1 camera returned no frame")
    # S1 stream startup can produce partially refreshed frames without a corrupt flag.
    time.sleep(1.2)
    frame = robot.camera.read_video_frame(timeout=3.0, strategy="newest")
    if frame is None or frame.is_corrupt:
        raise RuntimeError("S1 camera returned no usable frame after startup")
    result = save_frame(frame, "observation.jpg")
finally:
    robot.camera.stop_video_stream()
"""


class RobotToolRunner:
    def __init__(
        self,
        executor: PythonExecutor,
        gateway: ModelGateway,
        *,
        policy: RobotCodePolicy | None = None,
    ) -> None:
        self.executor = executor
        self.gateway = gateway
        self.policy = policy or RobotCodePolicy()

    def run(self, call: ToolCall) -> ToolResult:
        try:
            if call.name == "execute_robot_python":
                output = self._execute_python(call.arguments)
                return ToolResult(call.call_id, call.name, output)
            if call.name == "observe_surroundings":
                output = self._observe(call.arguments)
                return ToolResult(call.call_id, call.name, output)
            if call.name == "sleep_session":
                return ToolResult(
                    call.call_id,
                    call.name,
                    {"ok": True, "state": "waiting_for_wake_phrase"},
                    end_session=True,
                )
            raise ValueError(f"unknown agent tool: {call.name}")
        except Exception as exc:
            return ToolResult(
                call.call_id,
                call.name,
                {
                    "ok": False,
                    "error": {"type": type(exc).__name__, "message": str(exc)},
                },
            )

    def emergency_stop(self) -> dict[str, Any]:
        code = """if robot is None:
    result = {"stopped": False, "reason": "robot is not connected"}
else:
    robot.stop()
    robot.disarm()
    result = {"stopped": True}
"""
        return self.executor.execute(code, robot_access="reuse", timeout_seconds=5.0)

    def close(self) -> None:
        try:
            self.executor.close()
        finally:
            close_gateway = getattr(self.gateway, "close", None)
            if callable(close_gateway):
                close_gateway()

    def _execute_python(self, arguments: dict[str, Any]) -> dict[str, Any]:
        code = arguments.get("code")
        intent = arguments.get("intent")
        if not isinstance(code, str) or not isinstance(intent, str):
            raise ValueError("execute_robot_python requires string intent and code")
        timeout = arguments.get("timeout_seconds")
        if timeout is not None and (
            isinstance(timeout, bool) or not isinstance(timeout, (int, float))
        ):
            raise ValueError("timeout_seconds must be a number or null")
        self.policy.validate(code)
        response = self.executor.execute(
            code,
            robot_access="auto",
            timeout_seconds=float(timeout) if timeout is not None else None,
        )
        return self._compact_execution(response, intent=intent)

    def _observe(self, arguments: dict[str, Any]) -> dict[str, Any]:
        question = arguments.get("question")
        if not isinstance(question, str) or not question.strip():
            raise ValueError("observe_surroundings requires a question")
        started = time.perf_counter()
        response = self.executor.execute(_OBSERVATION_CODE, robot_access="auto")
        capture_ms = (time.perf_counter() - started) * 1000
        compact = self._compact_execution(response, intent="观察当前前向画面")
        if not response.get("ok"):
            return compact

        raw_path = response.get("result")
        artifact_dir = Path(str(response.get("artifact_dir", ""))).resolve()
        if not isinstance(raw_path, str):
            raise RuntimeError("camera capture returned no image path")
        image_path = Path(raw_path).resolve()
        if artifact_dir not in image_path.parents or not image_path.is_file():
            raise RuntimeError("camera capture returned an invalid image artifact")
        return {
            **compact,
            "image_path": str(image_path),
            "question": question.strip(),
            "scope": "current_forward_camera_frame",
            "timings": {"capture_ms": capture_ms},
        }

    @staticmethod
    def _compact_execution(response: dict[str, Any], *, intent: str) -> dict[str, Any]:
        payload: dict[str, Any] = {
            "ok": bool(response.get("ok")),
            "intent": intent,
            "result": response.get("result"),
            "events": response.get("events", []),
            "artifacts": response.get("artifacts", []),
            "connection": response.get("connection"),
            "duration_seconds": response.get("duration_seconds"),
        }
        if response.get("stdout"):
            payload["stdout"] = response["stdout"]
        if response.get("stderr"):
            payload["stderr"] = response["stderr"]
        if response.get("error"):
            payload["error"] = response["error"]
        if response.get("cleanup_errors"):
            payload["cleanup_errors"] = response["cleanup_errors"]
        return payload
