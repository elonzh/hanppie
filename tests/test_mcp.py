from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import ANY

import pytest
import tomlkit
from mcp.client.session import ClientSession
from mcp.client.stdio import StdioServerParameters, stdio_client

from hanppie.lab.protocol import RobotBroadcast
from hanppie.mcp.executor import ExecutorConfig, PythonExecutor
from hanppie.mcp.install import (
    build_server_entry,
    install_codex_server,
    resolve_codex_config_path,
)
from hanppie.mcp.runtime import PersistentRuntime, RuntimeConfig
from hanppie.mcp.server import create_server


class FakePart:
    def __init__(self) -> None:
        self.calls: list[tuple[str, object]] = []

    def drive_speed(self, **values: object) -> None:
        self.calls.append(("drive_speed", values))

    def drive_wheels(self, *values: object, timeout: float) -> str:
        self.calls.append(("drive_wheels", (*values, timeout)))
        return "wheels"

    def stop(self) -> None:
        self.calls.append(("stop", None))


class FakeRobot:
    instances: list[FakeRobot] = []

    def __init__(self, **values: object) -> None:
        self.values = values
        self.connected = False
        self.control_mode = False
        self.armed = False
        self.info = SimpleNamespace(ip=values["robot_ip"], state="fake")
        self.odometry = None
        self.gimbal_telemetry = None
        self.chassis = FakePart()
        self.gimbal = FakePart()
        self.camera = SimpleNamespace()
        self.audio = SimpleNamespace()
        self.calls: list[tuple[str, object]] = []
        self.instances.append(self)

    def initialize(self, **values: object) -> bool:
        self.calls.append(("initialize", values))
        self.connected = True
        return True

    def enter_control_mode(self) -> tuple[int, ...]:
        self.control_mode = True
        self.calls.append(("enter_control_mode", None))
        return (1,)

    def arm(self) -> None:
        self.armed = True
        self.calls.append(("arm", None))

    def disarm(self) -> None:
        self.armed = False
        self.calls.append(("disarm", None))

    def stop(self) -> None:
        self.calls.append(("stop", None))

    def close(self) -> None:
        self.connected = False
        self.calls.append(("close", None))

    def set_led(self, **values: object) -> dict[str, object]:
        self.calls.append(("set_led", values))
        return values

    def set_muzzle_led(self, **values: object) -> dict[str, object]:
        self.calls.append(("set_muzzle_led", values))
        return values

    def play_sound(self, sound_id: int, *, timeout: float) -> tuple[int, float]:
        return sound_id, timeout

    def capture(self, *, timeout: float) -> float:
        return timeout

    def fire_infrared(self, *, lease_seconds: float) -> None:
        if not self.armed:
            raise PermissionError("robot is not armed")
        self.calls.append(("fire_infrared", lease_seconds))


class FakeBridge:
    def __init__(self) -> None:
        self.command_sequence = 0
        self.last_telemetry: SimpleNamespace | None = None
        self.calls: list[str] = []

    def _command(self, name: str, **values: object) -> bool:
        self.command_sequence += 1
        self.calls.append(name)
        self.last_telemetry = SimpleNamespace(
            values={
                "rx_command_seq": self.command_sequence,
                "last_command": name,
                "last_command_ok": True,
                **values,
            }
        )
        return True

    def arm(self) -> bool:
        return self._command("system.arm", armed=True)

    def disarm(self) -> bool:
        return self._command("system.disarm", armed=False)

    def stop_robot(self) -> bool:
        return self._command("system.stop", motion_active=False)

    def fire(self, fire_type: str) -> bool:
        assert fire_type == "gel"
        return self._command(
            "blaster.fire_gel",
            last_fire_led_ok=True,
            last_fire_sound_ok=True,
            last_fire_actuator_ok=True,
        )


class FakeLabRobot:
    instances: list[FakeLabRobot] = []

    def __init__(self, **values: object) -> None:
        self.values = values
        self.connected = False
        self.info = SimpleNamespace(ip=values["robot_ip"], state="fake-lab")
        self.camera = SimpleNamespace()
        self.audio = SimpleNamespace()
        self.bridge = FakeBridge()
        self.chassis = FakePart()
        self.gimbal = FakePart()
        self.calls: list[tuple[str, object]] = []
        self.instances.append(self)

    def initialize(self, **values: object) -> bool:
        self.calls.append(("initialize", values))
        self.connected = True
        return True

    def enter_lab(self) -> None:
        self.calls.append(("enter_lab", None))

    def upload_lab_bridge(self) -> str:
        self.calls.append(("upload_lab_bridge", None))
        return "digest"

    def start_lab_program(self, digest: str) -> None:
        self.calls.append(("start_lab_program", digest))

    def start_lab_bridge(self) -> None:
        self.calls.append(("start_lab_bridge", None))

    def fire(self, fire_type: str) -> bool:
        self.calls.append(("fire", fire_type))
        return self.bridge.fire(fire_type)

    def set_led(self, **values: object) -> dict[str, object]:
        self.calls.append(("set_led", values))
        return values

    def close(self) -> None:
        self.connected = False
        self.calls.append(("close", None))


class FlakyLabRobot(FakeLabRobot):
    instances: list[FlakyLabRobot] = []

    def start_lab_bridge(self) -> None:
        super().start_lab_bridge()
        if len(self.instances) == 1:
            raise TimeoutError("matching-session telemetry")


class FailingLabRobot(FakeLabRobot):
    instances: list[FailingLabRobot] = []

    def start_lab_bridge(self) -> None:
        super().start_lab_bridge()
        raise TimeoutError("matching-session telemetry")


def make_config(tmp_path: Path, **values: object) -> ExecutorConfig:
    defaults: dict[str, object] = {
        "robot_ip": "192.0.2.10",
        "appid": "b6359877",
        "artifact_base": tmp_path,
    }
    defaults.update(values)
    return ExecutorConfig(**defaults)  # type: ignore[arg-type]


def test_executor_config_accepts_discovery_or_explicit_valid_target(tmp_path: Path) -> None:
    automatic = ExecutorConfig(artifact_base=tmp_path)

    assert ExecutorConfig().artifact_base == Path(".hanppie/mcp")
    assert automatic.robot_ip is None
    assert automatic.appid is None
    assert make_config(tmp_path).appid == "b6359877"
    with pytest.raises(ValueError, match="8 hexadecimal"):
        make_config(tmp_path, appid="invalid")
    with pytest.raises(ValueError, match="00000000"):
        make_config(tmp_path, appid="00000000")
    assert ExecutorConfig(artifact_base=tmp_path).robot_ip is None


def test_executor_discovers_one_target_lazily_and_caches_it(tmp_path: Path) -> None:
    calls: list[float] = []

    def discover(timeout: float) -> list[RobotBroadcast]:
        calls.append(timeout)
        return [
            RobotBroadcast("192.0.2.9", "AA:BB:CC:DD:EE:00", "00000000", True),
            RobotBroadcast("192.0.2.10", "AA:BB:CC:DD:EE:01", "b6359877", False),
        ]

    executor = PythonExecutor(
        ExecutorConfig(artifact_base=tmp_path, discovery_timeout=0.25),
        discover=discover,
    )

    context = executor.describe_context()

    assert context["target"]["resolved"] is False
    assert context["server"]["context_schema_version"] == 2
    assert context["execution"]["robot_access"]["none"].startswith("always inject")
    assert "fire: bool" in context["robot_api"]["robot.set_muzzle_led"]
    assert context["capabilities"]["exact_chassis_angle_control"] is False
    assert executor._resolve_target() == ("192.0.2.10", "b6359877")
    assert executor._resolve_target() == ("192.0.2.10", "b6359877")
    assert calls == [0.25]
    assert executor.describe_context()["target"]["robot_ip"] == "192.0.2.10"


def test_executor_rejects_ambiguous_targets(tmp_path: Path) -> None:
    robots = [
        RobotBroadcast("192.0.2.10", "AA:BB:CC:DD:EE:01", "b6359877", False),
        RobotBroadcast("192.0.2.11", "AA:BB:CC:DD:EE:02", "785a4e73", False),
    ]
    executor = PythonExecutor(
        ExecutorConfig(artifact_base=tmp_path),
        discover=lambda _: robots,
    )

    with pytest.raises(RuntimeError, match="Multiple S1"):
        executor._resolve_target()


def test_runtime_exposes_direct_actions_without_server_permission_gates(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    runtime = PersistentRuntime(RuntimeConfig(), direct_factory=FakeRobot)  # type: ignore[arg-type]

    response = runtime.execute(
        code=(
            "robot.arm()\n"
            "robot.chassis.drive_speed(x=0.1, lease_seconds=2.0)\n"
            "robot.gimbal.drive_speed(yaw_speed=10, lease_seconds=2.0)\n"
            "robot.fire_infrared(lease_seconds=2.0)\n"
            "result = 'done'"
        ),
        output_dir=tmp_path,
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert response["ok"]
    assert response["result"] == "done"
    assert FakeRobot.instances[0].chassis.calls[0][1]["lease_seconds"] == 2.0
    assert ("fire_infrared", 2.0) in FakeRobot.instances[0].calls
    runtime.disconnect()


def test_runtime_reuses_connection_but_disarms_after_every_call(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    runtime = PersistentRuntime(
        RuntimeConfig(),
        direct_factory=FakeRobot,  # type: ignore[arg-type]
    )

    first = runtime.execute(
        code=(
            "robot.arm()\n"
            "robot.chassis.drive_speed(x=0.1, lease_seconds=0.25)\n"
            "result = robot.set_led(red=0, green=255, blue=0)"
        ),
        output_dir=tmp_path / "first",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )
    second = runtime.execute(
        code="result = robot.status()",
        output_dir=tmp_path / "second",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert first["ok"] and second["ok"]
    assert len(FakeRobot.instances) == 1
    assert first["connection"]["connected"]
    assert second["connection"]["connection_generation"] == 1
    assert FakeRobot.instances[0].calls.count(("initialize", ANY)) == 1
    assert FakeRobot.instances[0].calls[-1] == ("disarm", None)
    assert ("close", None) not in FakeRobot.instances[0].calls

    assert runtime.disconnect() == []
    assert FakeRobot.instances[0].calls[-1] == ("close", None)


def test_runtime_reports_error_without_dropping_healthy_connection(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    runtime = PersistentRuntime(RuntimeConfig(), direct_factory=FakeRobot)  # type: ignore[arg-type]

    response = runtime.execute(
        code="raise RuntimeError('user code failed')",
        output_dir=tmp_path,
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert not response["ok"]
    assert response["error"]["type"] == "RuntimeError"
    assert response["connection"]["connected"]
    assert ("close", None) not in FakeRobot.instances[0].calls
    runtime.disconnect()


def test_runtime_reuses_lab_backend_for_shared_actions_and_switches_only_when_needed(
    tmp_path: Path,
) -> None:
    FakeRobot.instances.clear()
    FakeLabRobot.instances.clear()
    runtime = PersistentRuntime(
        RuntimeConfig(),
        direct_factory=FakeRobot,  # type: ignore[arg-type]
        lab_factory=FakeLabRobot,  # type: ignore[arg-type]
    )

    first = runtime.execute(
        code="result = robot.fire_gel()",
        output_dir=tmp_path / "gel-first",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )
    second = runtime.execute(
        code="result = robot.fire('gel')",
        output_dir=tmp_path / "gel-second",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert first["ok"] and second["ok"]
    assert first["result"] == {
        "fire_type": "gel",
        "count": 1,
        "acknowledged": True,
        "effects": {"muzzle_led": True, "shoot_sound": True, "actuator": True},
    }
    assert len(FakeLabRobot.instances) == 1
    assert FakeLabRobot.instances[0].calls.count(("initialize", ANY)) == 1
    assert FakeLabRobot.instances[0].calls.count(("fire", "gel")) == 2
    assert second["connection"]["backend"] == "lab"
    assert ("close", None) not in FakeLabRobot.instances[0].calls

    shared = runtime.execute(
        code="result = robot.set_led(red=1, green=2, blue=3)",
        output_dir=tmp_path / "lab-led",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert shared["ok"]
    assert shared["connection"]["backend"] == "lab"
    assert len(FakeRobot.instances) == 1
    assert FakeLabRobot.instances[0].calls[-1][0] == "set_led"
    assert ("close", None) not in FakeLabRobot.instances[0].calls

    stopped = runtime.execute(
        code=("robot.chassis.stop()\nrobot.gimbal.stop()\nrobot.stop()\nresult = robot.status()"),
        output_dir=tmp_path / "lab-stop",
        robot_access="reuse",
        robot_ip=None,
        appid=None,
    )

    assert stopped["ok"]
    assert stopped["connection"]["backend"] == "lab"
    assert len(FakeRobot.instances) == 1
    assert ("stop", None) in FakeLabRobot.instances[0].chassis.calls
    assert ("stop", None) in FakeLabRobot.instances[0].gimbal.calls

    direct = runtime.execute(
        code="result = robot.set_muzzle_led(fire=False, enabled=False)",
        output_dir=tmp_path / "direct-again",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert direct["ok"]
    assert direct["connection"]["backend"] == "direct"
    assert len(FakeRobot.instances) == 2
    assert FakeLabRobot.instances[0].calls[-1] == ("close", None)
    runtime.disconnect()


def test_runtime_retries_a_failed_lab_transition_and_reports_attempts(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    FlakyLabRobot.instances.clear()
    runtime = PersistentRuntime(
        RuntimeConfig(lab_transition_attempts=2, lab_retry_settle=0),
        direct_factory=FakeRobot,  # type: ignore[arg-type]
        lab_factory=FlakyLabRobot,  # type: ignore[arg-type]
    )

    response = runtime.execute(
        code="result = robot.fire_gel()",
        output_dir=tmp_path,
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert response["ok"]
    assert response["connection"]["backend"] == "lab"
    assert len(FlakyLabRobot.instances) == 2
    assert FlakyLabRobot.instances[0].calls[-1] == ("close", None)
    assert response["connection"]["transition"]["last"] == {
        "timestamp": ANY,
        "from": "direct",
        "to": "lab",
        "ok": True,
        "attempts": 2,
        "duration_seconds": ANY,
        "error": None,
        "rollback_backend": None,
        "rollback_error": None,
    }
    runtime.disconnect()


def test_runtime_rolls_back_to_direct_after_lab_transition_failure(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    FailingLabRobot.instances.clear()
    runtime = PersistentRuntime(
        RuntimeConfig(lab_transition_attempts=2, lab_retry_settle=0),
        direct_factory=FakeRobot,  # type: ignore[arg-type]
        lab_factory=FailingLabRobot,  # type: ignore[arg-type]
    )

    response = runtime.execute(
        code="result = robot.fire_gel()",
        output_dir=tmp_path,
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )

    assert not response["ok"]
    assert response["error"]["type"] == "RuntimeError"
    assert "rolled back to direct" in response["error"]["message"]
    assert response["connection"]["backend"] == "direct"
    assert response["connection"]["transition"]["last"]["ok"] is False
    assert response["connection"]["transition"]["last"]["attempts"] == 2
    assert response["connection"]["transition"]["last"]["rollback_backend"] == "direct"
    assert len(FailingLabRobot.instances) == 2
    assert len(FakeRobot.instances) == 2
    runtime.disconnect()


def test_runtime_robot_access_modes_and_failure_checkpoints(tmp_path: Path) -> None:
    FakeRobot.instances.clear()
    runtime = PersistentRuntime(RuntimeConfig(), direct_factory=FakeRobot)  # type: ignore[arg-type]

    connected = runtime.execute(
        code="result = robot.status()['backend']",
        output_dir=tmp_path / "auto",
        robot_access="auto",
        robot_ip="192.0.2.10",
        appid="b6359877",
    )
    reused = runtime.execute(
        code="result = robot.status()['backend']",
        output_dir=tmp_path / "reuse",
        robot_access="reuse",
        robot_ip=None,
        appid=None,
    )
    host_only = runtime.execute(
        code="result = robot is None",
        output_dir=tmp_path / "none",
        robot_access="none",
        robot_ip=None,
        appid=None,
    )
    failed = runtime.execute(
        code=(
            "checkpoint('rotation_complete', nominal_degrees=360)\nraise RuntimeError('gel failed')"
        ),
        output_dir=tmp_path / "failed",
        robot_access="reuse",
        robot_ip=None,
        appid=None,
    )

    assert connected["result"] == "direct"
    assert reused["result"] == "direct"
    assert host_only["result"] is True
    assert len(FakeRobot.instances) == 1
    assert not failed["ok"]
    assert failed["events"][0]["name"] == "rotation_complete"
    assert failed["events"][0]["data"] == {"nominal_degrees": 360}
    event = json.loads((tmp_path / "failed" / "events.jsonl").read_text(encoding="utf-8").strip())
    assert event == failed["events"][0]
    runtime.disconnect()


def test_executor_keeps_worker_for_host_python_and_recovers_after_timeout(tmp_path: Path) -> None:
    executor = PythonExecutor(
        make_config(
            tmp_path,
            execution_timeout=0.1,
            max_execution_timeout=2.0,
            max_output_chars=20,
        )
    )
    session_dir = executor._recorder.session_dir
    recovered_process = None
    try:
        # Cold spawn must not compete with the deliberately tiny infinite-loop timeout.
        first = executor.execute(
            "print('x' * 100)\nresult = 6 * 7", connect_robot=False, timeout_seconds=2.0
        )
        first_pid = executor._process.pid
        second = executor.execute("result = 'same worker'", connect_robot=False)
        second_pid = executor._process.pid
        timed_out = executor.execute("while True:\n    pass", connect_robot=False)
        recovered = executor.execute(
            "result = 'recovered'", connect_robot=False, timeout_seconds=2.0
        )
        recovered_process = executor._process

        assert first["result"] == 42
        assert "omitted" in first["stdout"]
        assert second["result"] == "same worker"
        assert second_pid == first_pid
        assert executor._process.pid != first_pid
        assert timed_out["timed_out"]
        assert recovered["result"] == "recovered"
        assert Path(first["artifact_dir"]).parent == session_dir / "calls"
    finally:
        executor.close()
    assert recovered_process is not None
    assert not recovered_process.is_alive()
    calls = [
        json.loads(line)
        for line in (session_dir / "calls.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert [call["tool"] for call in calls] == ["execute_python"] * 8
    assert [call["event"] for call in calls] == ["started", "completed"] * 4
    assert calls[0]["arguments"]["code"] == "print('x' * 100)\nresult = 6 * 7"
    assert calls[1]["result"]["result"] == 42
    assert calls[5]["status"] == "error"
    server_log = (session_dir / "server.log").read_text(encoding="utf-8")
    assert "session.start" in server_log
    assert "worker.start" in server_log
    assert "tool.call" in server_log
    assert "session.stop" in server_log


def test_executor_records_rejected_tool_calls_without_starting_worker(tmp_path: Path) -> None:
    executor = PythonExecutor(make_config(tmp_path))
    session_dir = executor._recorder.session_dir
    try:
        with pytest.raises(ValueError, match="must not be empty"):
            executor.execute("", connect_robot=False)
    finally:
        executor.close()

    calls = [
        json.loads(line)
        for line in (session_dir / "calls.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert [call["event"] for call in calls] == ["started", "completed"]
    assert calls[0]["tool"] == "execute_python"
    assert calls[0]["arguments"]["code"] == ""
    assert calls[1]["status"] == "error"
    assert calls[1]["error"]["type"] == "ValueError"


def test_executor_startup_and_close_without_calls_create_no_artifacts(tmp_path: Path) -> None:
    artifact_base = tmp_path / "unused" / ".hanppie" / "mcp"

    executor = PythonExecutor(ExecutorConfig(artifact_base=artifact_base))
    context = executor.context_snapshot()
    executor.close()

    assert context["execution"]["persistent_worker"] is True
    assert not artifact_base.exists()


def test_install_preserves_other_config_and_is_idempotent(tmp_path: Path) -> None:
    codex_home = tmp_path / ".codex"
    config_path = codex_home / "config.toml"
    codex_home.mkdir()
    config_path.write_text(
        "model = 'gpt-5.6-sol'\n\n[mcp_servers.other]\ncommand = 'other'\n",
        encoding="utf-8",
    )
    entry = build_server_entry(
        ["--robot-ip", "192.0.2.10"],
        executable=r"C:\Users\Alice\hanppie\Scripts\python.exe",
    )

    installed = install_codex_server(entry, codex_home=codex_home)
    again = install_codex_server(entry, codex_home=codex_home)
    parsed = tomlkit.parse(config_path.read_text(encoding="utf-8")).unwrap()

    assert installed["changed"] is True
    assert again["changed"] is False
    assert parsed["model"] == "gpt-5.6-sol"
    assert parsed["mcp_servers"]["other"]["command"] == "other"
    assert parsed["mcp_servers"]["hanppie"]["command"].startswith("C:\\Users")
    assert parsed["mcp_servers"]["hanppie"]["default_tools_approval_mode"] == "writes"


def test_server_entry_preserves_virtual_environment_executable(monkeypatch) -> None:
    monkeypatch.setattr(sys, "executable", "/workspace/hanppie/.venv/bin/python")

    entry = build_server_entry([])

    assert entry["command"] == "/workspace/hanppie/.venv/bin/python"


def test_install_refuses_conflict_without_replace(tmp_path: Path) -> None:
    codex_home = tmp_path / "codex"
    entry = build_server_entry([], executable=sys.executable)
    install_codex_server(entry, codex_home=codex_home)

    changed = build_server_entry(["--debug"], executable=sys.executable)
    with pytest.raises(ValueError, match="--replace"):
        install_codex_server(changed, codex_home=codex_home)
    result = install_codex_server(changed, codex_home=codex_home, replace=True)

    assert result["changed"] is True


def test_config_path_honors_codex_home_and_project_root(tmp_path: Path) -> None:
    project = tmp_path / "project"
    nested = project / "src" / "package"
    nested.mkdir(parents=True)
    (project / "pyproject.toml").write_text("[project]\nname='demo'\n", encoding="utf-8")

    user_path = resolve_codex_config_path(
        "user",
        environment={"CODEX_HOME": str(tmp_path / "custom")},
    )
    project_path = resolve_codex_config_path("project", project_dir=nested)

    assert user_path == tmp_path / "custom" / "config.toml"
    assert project_path == project / ".codex" / "config.toml"


def test_server_exposes_persistent_workflow_and_annotations() -> None:
    async def inspect_tools() -> dict[str, object]:
        return {tool.name: tool for tool in await create_server(ExecutorConfig()).list_tools()}

    tools = asyncio.run(inspect_tools())

    assert set(tools) == {
        "get_python_context",
        "get_connection_status",
        "connect_robot",
        "execute_python",
        "disconnect_robot",
    }
    assert tools["get_connection_status"].annotations.readOnlyHint is True
    assert tools["execute_python"].annotations.destructiveHint is True


def test_stdio_server_executes_host_python_without_robot(tmp_path: Path) -> None:
    async def exercise() -> None:
        parameters = StdioServerParameters(
            command=sys.executable,
            args=[
                "-m",
                "hanppie",
                "mcp",
                "serve",
                "--artifact-dir",
                str(tmp_path),
            ],
        )
        async with stdio_client(parameters) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                tools = await session.list_tools()
                context_response = await session.call_tool("get_python_context", {})
                context_payload = json.loads(context_response.content[0].text)
                response = await session.call_tool(
                    "execute_python",
                    {"code": "result = 6 * 7", "connect_robot": False},
                )
                payload = json.loads(response.content[0].text)
                failed = await session.call_tool(
                    "execute_python",
                    {"code": "raise RuntimeError('boom')", "robot_access": "none"},
                )

        assert {tool.name for tool in tools.tools} >= {"connect_robot", "execute_python"}
        assert not context_response.isError
        assert not response.isError
        assert failed.isError
        assert "RuntimeError" in failed.content[0].text
        assert payload["result"] == 42
        session_dir = Path(context_payload["recording"]["session_dir"])
        assert session_dir.parent == tmp_path / "sessions"
        calls = [
            json.loads(line)
            for line in (session_dir / "calls.jsonl").read_text(encoding="utf-8").splitlines()
        ]
        assert [call["tool"] for call in calls] == [
            "get_python_context",
            "get_python_context",
            "execute_python",
            "execute_python",
            "execute_python",
            "execute_python",
        ]
        assert [call["event"] for call in calls] == [
            "started",
            "completed",
            "started",
            "completed",
            "started",
            "completed",
        ]
        assert Path(payload["artifact_dir"]).parent == session_dir / "calls"

    asyncio.run(exercise())


def test_stdio_server_startup_without_tool_calls_creates_no_artifacts(tmp_path: Path) -> None:
    artifact_base = tmp_path / "unused-mcp-artifacts"

    async def exercise() -> None:
        parameters = StdioServerParameters(
            command=sys.executable,
            args=[
                "-m",
                "hanppie",
                "mcp",
                "serve",
                "--artifact-dir",
                str(artifact_base),
            ],
        )
        async with stdio_client(parameters) as (read_stream, write_stream):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                await session.list_tools()

    asyncio.run(exercise())

    assert not artifact_base.exists()
