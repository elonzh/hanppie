"""Data contracts shared by the voice-agent layers."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Literal, Protocol

from hanppie.mcp.executor import ExecutorConfig


@dataclass(frozen=True)
class AgentConfig:
    """Configuration for one host-resident conversational agent."""

    executor: ExecutorConfig = field(
        default_factory=lambda: ExecutorConfig(artifact_base=Path(".hanppie/agent/runtime"))
    )
    wake_phrases: tuple[str, ...] = ("小憨批", "小憨皮")
    active_timeout_seconds: float = 45.0
    auth_provider: Literal["auto", "codex", "api-key"] = "auto"
    model: str = "gpt-5.6"
    vision_model: str | None = None
    codex_model: str = "gpt-5.6-sol"
    codex_vision_model: str | None = None
    reasoning_effort: str = "low"
    model_timeout_seconds: float = 30.0
    transcription_model: str = "gpt-transcribe"
    local_transcription_model: str = "small"
    tts_model: str = "gpt-4o-mini-tts"
    voice: str = "coral"
    language: str | None = "zh"
    artifact_base: Path = Path(".hanppie/agent")
    max_tool_rounds: int = 4
    tts_enabled: bool = True

    def __post_init__(self) -> None:
        phrases = tuple(phrase.strip() for phrase in self.wake_phrases if phrase.strip())
        if not phrases:
            raise ValueError("at least one wake phrase is required")
        if self.active_timeout_seconds <= 0:
            raise ValueError("active_timeout_seconds must be greater than 0")
        if self.max_tool_rounds <= 0:
            raise ValueError("max_tool_rounds must be greater than 0")
        if self.auth_provider not in {"auto", "codex", "api-key"}:
            raise ValueError("auth_provider must be auto, codex, or api-key")
        for name, value in (
            ("model", self.model),
            ("transcription_model", self.transcription_model),
            ("local_transcription_model", self.local_transcription_model),
            ("tts_model", self.tts_model),
            ("voice", self.voice),
        ):
            if not value.strip():
                raise ValueError(f"{name} must not be empty")
        if not self.codex_model.strip():
            raise ValueError("codex_model must not be empty")
        if self.reasoning_effort not in {"none", "minimal", "low", "medium", "high", "xhigh"}:
            raise ValueError("invalid reasoning_effort")
        if self.model_timeout_seconds <= 0:
            raise ValueError("model_timeout_seconds must be positive")
        object.__setattr__(self, "wake_phrases", phrases)


@dataclass(frozen=True)
class ToolCall:
    name: str
    call_id: str
    arguments: dict[str, Any]


@dataclass(frozen=True)
class ModelTurn:
    response_id: str
    text: str
    tool_calls: tuple[ToolCall, ...] = ()
    metrics: dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class ToolResult:
    call_id: str
    name: str
    output: dict[str, Any]
    end_session: bool = False


@dataclass(frozen=True)
class AgentReply:
    text: str
    end_session: bool = False
    tool_results: tuple[ToolResult, ...] = ()
    timings: tuple[dict[str, Any], ...] = ()
    failed: bool = False


@dataclass(frozen=True)
class WakeDecision:
    accepted: bool
    command: str | None = None
    woke: bool = False


class ModelGateway(Protocol):
    def respond(
        self,
        user_text: str,
        *,
        previous_response_id: str | None,
    ) -> ModelTurn: ...

    def continue_with_tools(
        self,
        tool_results: list[ToolResult],
        *,
        previous_response_id: str,
    ) -> ModelTurn: ...

    def describe_image(self, image_path: Path, question: str) -> str: ...


class ConversationAgent(Protocol):
    def invoke(self, text: str, *, thread_id: str) -> AgentReply: ...


class Listener(Protocol):
    def listen(self) -> bytes: ...


class Transcriber(Protocol):
    def transcribe(self, wav_data: bytes) -> str: ...


class Speaker(Protocol):
    def speak(self, text: str) -> None: ...
