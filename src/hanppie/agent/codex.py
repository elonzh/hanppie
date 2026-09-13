"""Direct ChatGPT OAuth transport for the consumer Codex Responses backend."""

from __future__ import annotations

import json
import time
import uuid
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from hanppie.agent.codex_auth import CodexOAuthManager
from hanppie.agent.gateway import TOOLS, OpenAIModelGateway, build_instructions, observation_inputs
from hanppie.agent.model import ModelTurn, ToolResult


class CodexModelGateway:
    """Run stateless streaming Responses calls with Hanppie-managed conversation state."""

    def __init__(
        self,
        oauth: CodexOAuthManager,
        *,
        model: str,
        vision_model: str | None,
        robot_context: dict[str, object],
        reasoning_effort: str = "low",
        timeout_seconds: float = 30.0,
    ) -> None:
        self._oauth = oauth
        self.model = model
        self.vision_model = vision_model or model
        self.instructions = build_instructions(robot_context)
        self.reasoning_effort = reasoning_effort
        self.timeout_seconds = timeout_seconds
        self._conversations: dict[str, list[dict[str, Any]]] = {}

    def respond(
        self,
        user_text: str,
        *,
        previous_response_id: str | None,
    ) -> ModelTurn:
        conversation_id = previous_response_id or f"codex-{uuid.uuid4().hex}"
        history = self._history(conversation_id, create=previous_response_id is None)
        history.append(
            {
                "role": "user",
                "content": [{"type": "input_text", "text": user_text}],
            }
        )
        response = self._request(
            model=self.model,
            instructions=self.instructions,
            input=history,
            tools=TOOLS,
            parallel_tool_calls=False,
        )
        history.extend(self._serialize_outputs(response.output))
        return self._parse_response(conversation_id, response)

    def continue_with_tools(
        self,
        tool_results: list[ToolResult],
        *,
        previous_response_id: str,
    ) -> ModelTurn:
        history = self._history(previous_response_id)
        history.extend(
            {
                "type": "function_call_output",
                "call_id": result.call_id,
                "output": json.dumps(result.output, ensure_ascii=False, default=str),
            }
            for result in tool_results
        )
        images = observation_inputs(tool_results)
        response = self._request(
            model=self.vision_model if images else self.model,
            instructions=self.instructions,
            input=history + images,
            tools=TOOLS,
            parallel_tool_calls=False,
        )
        history.extend(self._serialize_outputs(response.output))
        return self._parse_response(previous_response_id, response)

    def describe_image(self, image_path: Path, question: str) -> str:
        mime = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
        import base64

        encoded = base64.b64encode(image_path.read_bytes()).decode("ascii")
        response = self._request(
            model=self.vision_model,
            instructions=(
                "请客观、简短地描述 RoboMaster 当前前向相机画面。只说明画面中可见内容，"
                "不要推断画面外的周围环境；不确定的对象明确说不确定。"
            ),
            input=[
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": question},
                        {
                            "type": "input_image",
                            "image_url": f"data:{mime};base64,{encoded}",
                            "detail": "auto",
                        },
                    ],
                }
            ],
        )
        if not response.output_text:
            raise RuntimeError("Codex 视觉模型没有返回画面描述")
        return response.output_text

    def close(self) -> None:
        self._conversations.clear()
        self._oauth.close()

    def _request(self, **arguments: Any) -> SimpleNamespace:
        started = time.perf_counter()
        for force_refresh in (False, True):
            try:
                client = self._oauth.client(force_refresh=force_refresh)
                stream = client.responses.create(
                    **arguments,
                    stream=True,
                    store=False,
                    reasoning={"effort": self.reasoning_effort},
                    timeout=self.timeout_seconds,
                )
                try:
                    return self._consume_stream(stream, started=started)
                finally:
                    close = getattr(stream, "close", None)
                    if callable(close):
                        close()
            except Exception as exc:
                if not force_refresh and self._status_code(exc) == 401:
                    continue
                raise
        raise RuntimeError("Codex OAuth 请求失败")

    def _history(self, conversation_id: str, *, create: bool = False) -> list[dict[str, Any]]:
        if create:
            return self._conversations.setdefault(conversation_id, [])
        try:
            return self._conversations[conversation_id]
        except KeyError as exc:
            raise RuntimeError("Codex 连续对话上下文已经失效") from exc

    @staticmethod
    def _consume_stream(stream: Any, *, started: float | None = None) -> SimpleNamespace:
        started = time.perf_counter() if started is None else started
        metrics: dict[str, Any] = {}
        response_id = ""
        text_parts: list[str] = []
        outputs: list[Any] = []
        completed = False
        for event in stream:
            event_type = getattr(event, "type", "")
            metrics.setdefault("first_event_ms", (time.perf_counter() - started) * 1000)
            if event_type in {
                "response.output_text.delta",
                "response.function_call_arguments.delta",
            }:
                metrics.setdefault("first_output_delta_ms", (time.perf_counter() - started) * 1000)
            if event_type == "response.created":
                response_id = str(getattr(getattr(event, "response", None), "id", ""))
            elif event_type == "response.output_text.delta":
                text_parts.append(str(getattr(event, "delta", "")))
            elif event_type == "response.output_item.done":
                outputs.append(getattr(event, "item", None))
                if getattr(getattr(event, "item", None), "type", None) == "function_call":
                    metrics.setdefault(
                        "first_tool_ready_ms", (time.perf_counter() - started) * 1000
                    )
            elif event_type == "response.completed":
                completed = True
                response = getattr(event, "response", None)
                if not response_id:
                    response_id = str(getattr(response, "id", ""))
                if not outputs:
                    outputs.extend(getattr(response, "output", ()) or ())
                usage = getattr(response, "usage", None)
                if usage is not None:
                    metrics["input_tokens"] = getattr(usage, "input_tokens", None)
                    metrics["output_tokens"] = getattr(usage, "output_tokens", None)
                break
        if not completed:
            raise RuntimeError("Codex Responses 流没有完成事件")
        return SimpleNamespace(
            id=response_id,
            output_text="".join(text_parts).strip(),
            output=tuple(item for item in outputs if item is not None),
            metrics=metrics,
        )

    @staticmethod
    def _serialize_outputs(outputs: tuple[Any, ...]) -> list[dict[str, Any]]:
        serialized: list[dict[str, Any]] = []
        for item in outputs:
            if isinstance(item, dict):
                value = dict(item)
            else:
                dump = getattr(item, "model_dump", None)
                if not callable(dump):
                    raise RuntimeError("Codex Responses 返回了无法序列化的输出项")
                value = dump(exclude_none=True)
            serialized.append(value)
        return serialized

    @staticmethod
    def _parse_response(conversation_id: str, response: SimpleNamespace) -> ModelTurn:
        parsed = OpenAIModelGateway._parse_response(response)
        return ModelTurn(conversation_id, parsed.text, parsed.tool_calls, response.metrics)

    @staticmethod
    def _status_code(exc: Exception) -> int | None:
        status = getattr(exc, "status_code", None)
        if isinstance(status, int):
            return status
        response = getattr(exc, "response", None)
        response_status = getattr(response, "status_code", None)
        return response_status if isinstance(response_status, int) else None
