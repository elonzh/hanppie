from __future__ import annotations

import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from hanppie.agent.gateway import OpenAIModelGateway, build_instructions
from hanppie.agent.model import ToolResult


class FakeResponses:
    def __init__(self, responses):
        self.responses = list(responses)
        self.calls = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self.responses.pop(0)


def test_api_observation_uses_vision_model_in_followup(tmp_path):
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    responses = FakeResponses([SimpleNamespace(id="reply", output_text="桌子", output=[])])
    gateway = OpenAIModelGateway(
        SimpleNamespace(responses=responses), model="text", vision_model="vision", robot_context={}
    )
    gateway.continue_with_tools(
        [ToolResult("c", "observe_surroundings", {"ok": True, "image_path": str(image)})],
        previous_response_id="prior",
    )
    assert len(responses.calls) == 1
    assert responses.calls[0]["model"] == "vision"
    assert "input_image" in json.dumps(responses.calls[0]["input"])


def test_gateway_parses_tool_calls_and_continues() -> None:
    first = SimpleNamespace(
        id="resp-1",
        output_text="",
        output=[
            SimpleNamespace(
                type="function_call",
                name="execute_robot_python",
                call_id="call-1",
                arguments=json.dumps(
                    {
                        "intent": "前进",
                        "code": "result = robot.status()",
                        "timeout_seconds": None,
                    }
                ),
            )
        ],
    )
    second = SimpleNamespace(id="resp-2", output_text="完成了", output=[])
    responses = FakeResponses([first, second])
    gateway = OpenAIModelGateway(
        SimpleNamespace(responses=responses),
        model="model",
        vision_model=None,
        robot_context={"robot_api": {"robot.status": "()"}},
    )

    turn = gateway.respond("前进", previous_response_id=None)
    final = gateway.continue_with_tools(
        [ToolResult("call-1", "execute_robot_python", {"ok": True})],
        previous_response_id=turn.response_id,
    )

    assert turn.tool_calls[0].arguments["intent"] == "前进"
    assert responses.calls[0]["parallel_tool_calls"] is False
    assert responses.calls[1]["previous_response_id"] == "resp-1"
    assert '"ok": true' in responses.calls[1]["input"][0]["output"]
    assert final.text == "完成了"


def test_gateway_describes_base64_image(tmp_path: Path) -> None:
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    responses = FakeResponses(
        [SimpleNamespace(id="vision-1", output_text="前方有一张桌子", output=[])]
    )
    gateway = OpenAIModelGateway(
        SimpleNamespace(responses=responses),
        model="model",
        vision_model="vision",
        robot_context={},
    )

    assert gateway.describe_image(image, "看到了什么") == "前方有一张桌子"
    payload = responses.calls[0]
    assert payload["model"] == "vision"
    assert payload["input"][0]["content"][1]["image_url"].startswith("data:image/jpeg;base64,")
    assert "execute_robot_python" in build_instructions({})


def test_gateway_rejects_malformed_model_output(tmp_path: Path) -> None:
    malformed = SimpleNamespace(
        id="response",
        output_text="",
        output=[SimpleNamespace(type="function_call", name="x", call_id="c", arguments="[")],
    )
    gateway = OpenAIModelGateway(
        SimpleNamespace(responses=FakeResponses([malformed])),
        model="model",
        vision_model=None,
        robot_context={},
    )
    with pytest.raises(RuntimeError, match="invalid tool arguments"):
        gateway.respond("x", previous_response_id=None)

    empty_vision = OpenAIModelGateway(
        SimpleNamespace(
            responses=FakeResponses([SimpleNamespace(id="vision", output_text="", output=[])])
        ),
        model="model",
        vision_model=None,
        robot_context={},
    )
    image = tmp_path / "x.png"
    image.write_bytes(b"png")
    with pytest.raises(RuntimeError, match="no description"):
        empty_vision.describe_image(image, "x")
