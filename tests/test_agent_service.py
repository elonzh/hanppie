from __future__ import annotations

from pathlib import Path

import pytest

from hanppie.agent.model import AgentConfig, AgentReply, ToolResult
from hanppie.agent.service import VoiceAgentService, WakeWordGate, build_voice_agent
from hanppie.mcp.executor import ExecutorConfig


class FakeConversation:
    def __init__(self) -> None:
        self.calls = []
        self.end_session = False

    def invoke(self, text, *, thread_id):
        self.calls.append((text, thread_id))
        return AgentReply(
            f"收到：{text}",
            end_session=self.end_session,
            tool_results=(ToolResult("call", "tool", {"ok": True}),),
        )


class FakeTools:
    def __init__(self) -> None:
        self.stop_calls = 0
        self.closed = False

    def emergency_stop(self):
        self.stop_calls += 1
        return {"ok": True, "result": {"stopped": True}}

    def close(self):
        self.closed = True


class FakeSpeaker:
    def __init__(self) -> None:
        self.messages = []

    def speak(self, text):
        self.messages.append(text)


def test_wake_word_gate_supports_active_followups_and_timeout() -> None:
    now = [10.0]
    gate = WakeWordGate(("小憨批",), active_timeout_seconds=5, clock=lambda: now[0])

    assert gate.accept("路过的人声").accepted is False
    wake = gate.accept("小憨批，观察一下")
    assert wake.woke is True
    assert wake.command == "观察一下"
    assert gate.accept("再看一次").command == "再看一次"
    now[0] = 20.0
    assert gate.accept("已经超时").accepted is False
    gate.sleep()
    assert gate.active is False


def test_voice_service_wake_continuous_stop_and_lazy_records(tmp_path: Path) -> None:
    config = AgentConfig(
        executor=ExecutorConfig(artifact_base=tmp_path / "runtime"),
        artifact_base=tmp_path / "agent",
        wake_phrases=("小憨批",),
    )
    conversation = FakeConversation()
    tools = FakeTools()
    speaker = FakeSpeaker()
    output = []
    service = VoiceAgentService(
        config=config,
        conversation=conversation,
        tools=tools,  # type: ignore[arg-type]
        listener=None,  # type: ignore[arg-type]
        transcriber=None,  # type: ignore[arg-type]
        speaker=speaker,
        emit=output.append,
    )

    assert service.process_transcript("无关背景") is None
    assert not (tmp_path / "agent").exists()
    assert service.process_transcript("小憨批") == AgentReply("我在。")
    reply = service.process_transcript("观察一下")
    stopped = service.process_transcript("停止")
    service.close()

    assert reply is not None and reply.text == "收到：观察一下"
    assert stopped is not None and stopped.text == "已经停下。"
    assert tools.stop_calls == 1
    assert tools.closed is True
    assert speaker.messages == ["我在。", "收到：观察一下", "已经停下。"]
    assert list((tmp_path / "agent" / "sessions").glob("*/events.jsonl"))
    assert output[0].startswith("你：")


def test_voice_service_ends_active_session(tmp_path: Path) -> None:
    config = AgentConfig(
        executor=ExecutorConfig(artifact_base=tmp_path / "runtime"),
        artifact_base=tmp_path / "agent",
        wake_phrases=("小憨批",),
        tts_enabled=False,
    )
    conversation = FakeConversation()
    conversation.end_session = True
    service = VoiceAgentService(
        config=config,
        conversation=conversation,
        tools=FakeTools(),  # type: ignore[arg-type]
        listener=None,  # type: ignore[arg-type]
        transcriber=None,  # type: ignore[arg-type]
        speaker=None,
    )

    reply = service.process_transcript("小憨批，退下")

    assert reply is not None and reply.end_session is True
    assert service.gate.active is False


def test_build_and_close_without_wake_creates_no_agent_artifacts(tmp_path: Path) -> None:
    config = AgentConfig(
        executor=ExecutorConfig(artifact_base=tmp_path / "agent" / "runtime"),
        artifact_base=tmp_path / "agent",
        tts_enabled=False,
    )

    service = build_voice_agent(config, client=object())
    service.close()

    assert not config.artifact_base.exists()


def test_agent_config_validates_auth_provider() -> None:
    with pytest.raises(ValueError, match="auth_provider"):
        AgentConfig(auth_provider="invalid")  # type: ignore[arg-type]


def test_text_stop_failure_is_not_reported_as_success(tmp_path: Path) -> None:
    tools = FakeTools()
    tools.emergency_stop = lambda: {"ok": False}
    service = VoiceAgentService(
        config=AgentConfig(artifact_base=tmp_path),
        conversation=FakeConversation(),
        tools=tools,
        listener=None,
        transcriber=None,
        speaker=None,
    )
    reply = service.process_prompt("停止")
    assert reply.failed is True
    assert "无法确认" in reply.text
    service.close()


def test_text_prompt_skips_audio_and_preserves_context(monkeypatch, tmp_path: Path) -> None:
    def no_audio(*args, **kwargs):
        pytest.fail("text mode initialized audio")

    for name in (
        "MicrophoneListener",
        "FasterWhisperTranscriber",
        "OpenAITranscriber",
        "SystemSpeaker",
        "OpenAITTSSpeaker",
    ):
        monkeypatch.setattr(f"hanppie.agent.service.{name}", no_audio)
    service = build_voice_agent(
        AgentConfig(artifact_base=tmp_path / "agent"), client=object(), text_only=True
    )
    conversation = FakeConversation()
    service.conversation = conversation
    try:
        assert not service.config.artifact_base.exists()
        with pytest.raises(ValueError, match="不能为空"):
            service.process_prompt("  ")
        service.process_prompt("观察一下")
        service.process_prompt("刚才看到了什么")
        assert [text for text, _ in conversation.calls] == ["观察一下", "刚才看到了什么"]
        assert len({thread for _, thread in conversation.calls}) == 1
        assert service.listener is None and service.speaker is None
        import json

        records = [
            json.loads(line) for line in service.recorder.events_path.read_text().splitlines()
        ]
        stages = [item["stage"] for item in records if item["event"] == "latency"]
        assert stages.count("input_to_reply_ready") == 2
        assert stages.count("input_to_delivery_complete") == 2
    finally:
        service.close()
