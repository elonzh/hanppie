from __future__ import annotations

from hanppie.agent.graph import LangGraphRobotAgent
from hanppie.agent.model import ModelTurn, ToolCall, ToolResult


class FakeGateway:
    def __init__(self) -> None:
        self.previous = []
        self.continued = []
        self.response_count = 0

    def respond(self, user_text, *, previous_response_id):
        self.previous.append((user_text, previous_response_id))
        self.response_count += 1
        if self.response_count == 1:
            return ModelTurn(
                "response-1",
                "",
                (ToolCall("execute_robot_python", "call-1", {"code": "x"}),),
            )
        return ModelTurn(f"response-{self.response_count + 1}", "这是连续对话")

    def continue_with_tools(self, tool_results, *, previous_response_id):
        self.continued.append((tool_results, previous_response_id))
        return ModelTurn("response-2", "已经完成")

    def describe_image(self, *_args):
        return "unused"


class FakeTools:
    def run(self, call):
        return ToolResult(call.call_id, call.name, {"ok": True})


def test_langgraph_runs_tools_and_retains_conversation_response() -> None:
    gateway = FakeGateway()
    events = []
    agent = LangGraphRobotAgent(gateway, FakeTools(), max_tool_rounds=2, progress=events.append)  # type: ignore[arg-type]

    first = agent.invoke("转一下", thread_id="thread")
    second = agent.invoke("刚才做了什么", thread_id="thread")

    assert first.text == "已经完成"
    assert first.tool_results[0].name == "execute_robot_python"
    assert gateway.continued[0][1] == "response-1"
    assert gateway.previous == [("转一下", None), ("刚才做了什么", "response-2")]
    assert second.text == "这是连续对话"
    assert [item["stage"] for item in first.timings] == ["model_plan", "tool", "model_followup"]
    assert [item["stage"] for item in second.timings] == ["model_plan"]
    assert all(item["duration_ms"] >= 0 for item in first.timings)
    assert [event["stage"] for event in events] == ["tool_dispatch", "tool_complete"]
    assert events[-1]["ok"] is True
    assert events[-1]["elapsed_ms"] >= events[0]["elapsed_ms"]


def test_langgraph_caps_tool_rounds() -> None:
    class LoopingGateway(FakeGateway):
        def respond(self, *_args, **_kwargs):
            return ModelTurn("one", "", (ToolCall("x", "one", {}),))

        def continue_with_tools(self, *_args, **_kwargs):
            return ModelTurn("two", "", (ToolCall("x", "two", {}),))

    agent = LangGraphRobotAgent(LoopingGateway(), FakeTools(), max_tool_rounds=1)  # type: ignore[arg-type]

    reply = agent.invoke("循环", thread_id="cap")

    assert "次数过多" in reply.text
    assert reply.failed is True
    assert len(reply.tool_results) == 1
