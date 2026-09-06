from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

import pytest

from hanppie.agent.model import ModelTurn, ToolCall
from hanppie.agent.tools import _OBSERVATION_CODE, RobotToolRunner


@pytest.mark.parametrize("missing,corrupt", [(False, False), (True, False), (False, True)])
def test_observation_waits_for_refreshed_frame_and_always_stops(missing, corrupt):
    first = SimpleNamespace(is_corrupt=False)
    refreshed = None if missing else SimpleNamespace(is_corrupt=corrupt)
    frames = iter([first, refreshed])
    stopped = []
    waits = []
    saved = []
    camera = SimpleNamespace(
        start_video_stream=lambda **kwargs: True,
        read_video_frame=lambda **kwargs: next(frames),
        stop_video_stream=lambda: stopped.append(True),
    )
    scope = {
        "robot": SimpleNamespace(camera=camera),
        "time": SimpleNamespace(sleep=waits.append),
        "save_frame": lambda frame, name: saved.append(frame),
    }
    if missing or corrupt:
        with pytest.raises(RuntimeError, match="usable frame"):
            exec(_OBSERVATION_CODE, scope)
        assert not saved
    else:
        exec(_OBSERVATION_CODE, scope)
        assert saved == [refreshed]
    assert waits == [1.2]
    assert stopped == [True]


class FakeGateway:
    def respond(self, *_args, **_kwargs):
        return ModelTurn("unused", "")

    def continue_with_tools(self, *_args, **_kwargs):
        return ModelTurn("unused", "")

    def describe_image(self, image_path: Path, question: str) -> str:
        assert image_path.read_bytes() == b"image"
        return f"画面回答：{question}"


class FakeExecutor:
    def __init__(self, tmp_path: Path) -> None:
        self.tmp_path = tmp_path
        self.calls = []
        self.closed = False

    def execute(self, code, **kwargs):
        self.calls.append((code, kwargs))
        call_dir = self.tmp_path / f"call-{len(self.calls)}"
        call_dir.mkdir()
        if "observation.jpg" in code:
            image = call_dir / "observation.jpg"
            image.write_bytes(b"image")
            result = str(image)
            artifacts = [{"path": str(image), "size": 5}]
        elif "robot is None" in code:
            result = {"stopped": True}
            artifacts = []
        else:
            result = {"done": True}
            artifacts = []
        return {
            "ok": True,
            "result": result,
            "events": [],
            "artifacts": artifacts,
            "connection": {"connected": True},
            "artifact_dir": str(call_dir),
            "duration_seconds": 0.1,
            "stdout": "",
            "stderr": "",
            "cleanup_errors": [],
        }

    def close(self):
        self.closed = True


def test_robot_tool_runner_executes_composed_code_and_observation(tmp_path: Path) -> None:
    executor = FakeExecutor(tmp_path)
    runner = RobotToolRunner(executor, FakeGateway())  # type: ignore[arg-type]
    action = runner.run(
        ToolCall(
            "execute_robot_python",
            "action",
            {"intent": "查状态", "code": "result = robot.status()", "timeout_seconds": 8},
        )
    )
    observation = runner.run(ToolCall("observe_surroundings", "vision", {"question": "附近有什么"}))

    assert action.output["ok"] is True
    assert action.output["result"] == {"done": True}
    assert observation.output["question"] == "附近有什么"
    assert Path(observation.output["image_path"]).read_bytes() == b"image"
    assert observation.output["timings"]["capture_ms"] >= 0
    assert observation.output["scope"] == "current_forward_camera_frame"
    assert executor.calls[0][1]["robot_access"] == "auto"
    assert executor.calls[0][1]["timeout_seconds"] == 8


def test_robot_tool_runner_sleep_stop_errors_and_close(tmp_path: Path) -> None:
    executor = FakeExecutor(tmp_path)
    runner = RobotToolRunner(executor, FakeGateway())  # type: ignore[arg-type]

    sleeping = runner.run(ToolCall("sleep_session", "sleep", {}))
    invalid = runner.run(ToolCall("missing", "bad", {}))
    rejected = runner.run(
        ToolCall(
            "execute_robot_python",
            "host",
            {"intent": "host", "code": "import os\nresult = robot.status()"},
        )
    )
    invalid_timeout = runner.run(
        ToolCall(
            "execute_robot_python",
            "timeout",
            {
                "intent": "查状态",
                "code": "result = robot.status()",
                "timeout_seconds": True,
            },
        )
    )
    stopped = runner.emergency_stop()
    runner.close()

    assert sleeping.end_session is True
    assert invalid.output["ok"] is False
    assert rejected.output["error"]["type"] == "GeneratedCodeRejected"
    assert invalid_timeout.output["error"]["type"] == "ValueError"
    assert stopped["result"]["stopped"] is True
    assert executor.calls[-1][1]["robot_access"] == "reuse"
    assert executor.closed is True
