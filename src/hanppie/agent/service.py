"""Wake-word session state and the top-level continuous voice loop."""

from __future__ import annotations

import os
import re
import time
import uuid
from collections.abc import Callable
from typing import Any

from hanppie.agent.audio import (
    FasterWhisperTranscriber,
    MicrophoneListener,
    OpenAITranscriber,
    OpenAITTSSpeaker,
    SystemSpeaker,
    VoiceActivityConfig,
)
from hanppie.agent.gateway import OpenAIModelGateway
from hanppie.agent.graph import LangGraphRobotAgent
from hanppie.agent.model import (
    AgentConfig,
    AgentReply,
    ConversationAgent,
    Listener,
    Speaker,
    Transcriber,
    WakeDecision,
)
from hanppie.agent.recorder import AgentRecorder
from hanppie.agent.tools import RobotToolRunner
from hanppie.mcp.executor import PythonExecutor


class WakeWordGate:
    def __init__(
        self,
        phrases: tuple[str, ...],
        *,
        active_timeout_seconds: float,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self.phrases = phrases
        self.active_timeout_seconds = active_timeout_seconds
        self._clock = clock
        self._active_until = 0.0

    @property
    def active(self) -> bool:
        return self._clock() < self._active_until

    def accept(self, transcript: str) -> WakeDecision:
        text = transcript.strip()
        if not text:
            return WakeDecision(False)

        was_active = self.active
        matched = self._find_phrase(text)
        if not was_active and matched is None:
            return WakeDecision(False)

        self._active_until = self._clock() + self.active_timeout_seconds
        if matched is not None:
            start, end = matched
            command = (text[:start] + text[end:]).strip()
            command = command.strip(" \t，,。.!！?？：:")
        else:
            command = text
        return WakeDecision(True, command=command or None, woke=not was_active)

    def sleep(self) -> None:
        self._active_until = 0.0

    def _find_phrase(self, text: str) -> tuple[int, int] | None:
        folded = text.casefold()
        matches = []
        for phrase in self.phrases:
            start = folded.find(phrase.casefold())
            if start >= 0:
                matches.append((start, start + len(phrase)))
        return min(matches) if matches else None


class VoiceAgentService:
    _stop_commands = {"停止", "停下", "停一下", "马上停止", "别动"}

    def __init__(
        self,
        *,
        config: AgentConfig,
        conversation: ConversationAgent,
        tools: RobotToolRunner,
        listener: Listener | None,
        transcriber: Transcriber | None,
        speaker: Speaker | None,
        emit: Callable[[str], None] = print,
    ) -> None:
        self.config = config
        self.conversation = conversation
        self.tools = tools
        self.listener = listener
        self.transcriber = transcriber
        self.speaker = speaker
        self.emit = emit
        self.gate = WakeWordGate(
            config.wake_phrases,
            active_timeout_seconds=config.active_timeout_seconds,
        )
        self.thread_id = self._new_thread_id()
        self.recorder = AgentRecorder(config.artifact_base, self.thread_id)
        self._turn_started: float | None = None
        if isinstance(conversation, LangGraphRobotAgent):
            conversation.progress = self._progress

    def run_forever(self) -> None:
        if self.listener is None or self.transcriber is None:
            raise RuntimeError("文本模式没有音频输入，请使用 process_prompt")
        self.emit("正在监听唤醒词：" + " / ".join(self.config.wake_phrases))
        try:
            while True:
                try:
                    audio = self.listener.listen()
                    started = time.perf_counter()
                    transcript = self.transcriber.transcribe(audio)
                    transcription_ms = (time.perf_counter() - started) * 1000
                    if transcript:
                        self.process_transcript(
                            transcript,
                            transcription_ms=transcription_ms,
                            input_started_at=self.listener.last_speech_end
                            if isinstance(self.listener, MicrophoneListener)
                            else started,
                        )
                except KeyboardInterrupt:
                    raise
                except Exception as exc:
                    self.emit(f"本轮语音处理失败：{type(exc).__name__}: {exc}")
                    time.sleep(0.5)
        except KeyboardInterrupt:
            self.emit("语音智能体已停止。")
        finally:
            self.close()

    def process_transcript(
        self,
        transcript: str,
        *,
        transcription_ms: float | None = None,
        input_started_at: float | None = None,
    ) -> AgentReply | None:
        decision = self.gate.accept(transcript)
        if not decision.accepted:
            return None
        if decision.woke:
            self._start_session()
        self._turn_started = input_started_at or time.perf_counter()

        self.emit(f"你：{transcript}")
        self.recorder.record("user.transcript", {"text": transcript})
        if transcription_ms is not None:
            self.recorder.record(
                "latency", {"stage": "transcription", "duration_ms": transcription_ms}
            )
        if decision.command is None:
            return self._deliver(AgentReply("我在。"))

        return self._execute_command(decision.command)

    def process_prompt(self, prompt: str) -> AgentReply:
        """Execute text without wake-word gating, retaining this session's context."""
        prompt = prompt.strip()
        if not prompt:
            raise ValueError("prompt 不能为空")
        self._turn_started = time.perf_counter()
        self.emit(f"你：{prompt}")
        self.recorder.record("user.prompt", {"text": prompt})
        return self._execute_command(prompt)

    def _execute_command(self, command: str) -> AgentReply:
        normalized = re.sub(r"[\s，,。.!！?？]", "", command)
        if normalized in self._stop_commands:
            execution = self.tools.emergency_stop()
            stopped = bool(execution.get("ok") and (execution.get("result") or {}).get("stopped"))
            text = "已经停下。" if stopped else "当前没有已连接的机器人需要停止。"
            if not execution.get("ok"):
                text = "停止指令执行失败，无法确认机器人已停下。"
            return self._deliver(AgentReply(text, failed=not bool(execution.get("ok"))))

        try:
            reply = self.conversation.invoke(command, thread_id=self.thread_id)
        except Exception as exc:
            self.recorder.record(
                "turn.failed",
                {
                    "error_type": type(exc).__name__,
                    "duration_ms": (time.perf_counter() - self._turn_started) * 1000
                    if self._turn_started is not None
                    else None,
                },
            )
            raise
        delivered = self._deliver(reply)
        if reply.end_session:
            self.gate.sleep()
        return delivered

    def close(self) -> None:
        self.tools.close()

    def _progress(self, event: dict[str, Any]) -> None:
        self.recorder.record("execution.progress", event)
        if event["stage"] == "tool_complete":
            status = "完成" if event["ok"] else "失败"
            self.emit(f"工具执行{status}：{event['name']}（{event['elapsed_ms'] / 1000:.2f}s）")

    def _deliver(self, reply: AgentReply) -> AgentReply:
        if self._turn_started is not None:
            self.recorder.record(
                "latency",
                {
                    "stage": "input_to_reply_ready",
                    "duration_ms": (time.perf_counter() - self._turn_started) * 1000,
                },
            )
        self.emit(f"小憨批：{reply.text}")
        self.recorder.record(
            "assistant.reply",
            {
                "text": reply.text,
                "end_session": reply.end_session,
                "failed": reply.failed,
                "timings": list(reply.timings),
                "tools": [
                    {
                        "name": result.name,
                        "call_id": result.call_id,
                        "output": result.output,
                    }
                    for result in reply.tool_results
                ],
            },
        )
        if self.speaker is not None:
            started = time.perf_counter()
            self.speaker.speak(reply.text)
            self.recorder.record(
                "latency",
                {"stage": "speech_playback", "duration_ms": (time.perf_counter() - started) * 1000},
            )
        if self._turn_started is not None:
            self.recorder.record(
                "latency",
                {
                    "stage": "input_to_delivery_complete",
                    "duration_ms": (time.perf_counter() - self._turn_started) * 1000,
                },
            )
        return reply

    def _start_session(self) -> None:
        self.thread_id = self._new_thread_id()
        self.recorder = AgentRecorder(self.config.artifact_base, self.thread_id)

    @staticmethod
    def _new_thread_id() -> str:
        return f"{time.strftime('%Y%m%d-%H%M%S')}-{uuid.uuid4().hex[:8]}"


def build_voice_agent(
    config: AgentConfig,
    *,
    audio_config: VoiceActivityConfig | None = None,
    audio_device: str | int | None = None,
    client: Any | None = None,
    text_only: bool = False,
    emit: Callable[[str], None] = print,
) -> VoiceAgentService:
    auth_provider = _resolve_auth_provider(config, client=client)
    if auth_provider == "api-key" and client is None:
        try:
            from openai import OpenAI
        except ImportError as exc:  # pragma: no cover - dependency failure path
            raise RuntimeError("OpenAI API Key 模式需要 openai Python SDK") from exc
        client = OpenAI()

    executor = PythonExecutor(config.executor)
    gateway: Any | None = None
    try:
        if auth_provider == "codex":
            from hanppie.agent.codex import CodexModelGateway
            from hanppie.agent.codex_auth import CodexOAuthManager

            oauth = CodexOAuthManager()
            gateway = CodexModelGateway(
                oauth,
                model=config.codex_model,
                vision_model=config.codex_vision_model,
                robot_context=executor.context_snapshot(),
                reasoning_effort=config.reasoning_effort,
                timeout_seconds=config.model_timeout_seconds,
            )
            oauth.client()
        else:
            gateway = OpenAIModelGateway(
                client,
                model=config.model,
                vision_model=config.vision_model,
                robot_context=executor.context_snapshot(),
            )
        tools = RobotToolRunner(executor, gateway)
        conversation = LangGraphRobotAgent(
            gateway,
            tools,
            max_tool_rounds=config.max_tool_rounds,
        )
        if text_only:
            return VoiceAgentService(
                config=config,
                conversation=conversation,
                tools=tools,
                listener=None,
                transcriber=None,
                speaker=None,
                emit=emit,
            )
        voice_activity = audio_config or VoiceActivityConfig()
        listener = MicrophoneListener(voice_activity, device=audio_device)
        if auth_provider == "codex":
            transcriber = FasterWhisperTranscriber(
                model=config.local_transcription_model,
                language=config.language,
            )
            speaker = SystemSpeaker() if config.tts_enabled else None
        else:
            transcriber = OpenAITranscriber(
                client,
                model=config.transcription_model,
                language=config.language,
            )
            speaker = (
                OpenAITTSSpeaker(
                    client,
                    model=config.tts_model,
                    voice=config.voice,
                    device=audio_device,
                )
                if config.tts_enabled
                else None
            )
    except Exception:
        try:
            executor.close()
        finally:
            close_gateway = getattr(gateway, "close", None)
            if callable(close_gateway):
                close_gateway()
        raise
    return VoiceAgentService(
        config=config,
        conversation=conversation,
        tools=tools,
        listener=listener,
        transcriber=transcriber,
        speaker=speaker,
        emit=emit,
    )


def _resolve_auth_provider(config: AgentConfig, *, client: Any | None) -> str:
    if config.auth_provider != "auto":
        return config.auth_provider
    if client is not None or os.environ.get("OPENAI_API_KEY"):
        return "api-key"
    return "codex"
