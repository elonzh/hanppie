"""LangGraph conversation and robot-tool loop."""

from __future__ import annotations

import time
from collections.abc import Callable
from typing import Any, Literal, TypedDict

from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph

from hanppie.agent.model import AgentReply, ModelGateway, ModelTurn, ToolResult
from hanppie.agent.tools import RobotToolRunner


class AgentState(TypedDict, total=False):
    user_text: str
    response_id: str
    model_turn: ModelTurn | None
    pending_tool_results: list[ToolResult]
    completed_tool_results: list[ToolResult]
    tool_rounds: int
    reply: AgentReply | None
    timings: list[dict[str, Any]]
    started_at: float


class LangGraphRobotAgent:
    def __init__(
        self,
        gateway: ModelGateway,
        tools: RobotToolRunner,
        *,
        max_tool_rounds: int = 4,
        progress: Callable[[dict[str, Any]], None] | None = None,
    ) -> None:
        self.gateway = gateway
        self.tools = tools
        self.max_tool_rounds = max_tool_rounds
        self.progress = progress

        graph = StateGraph(AgentState)
        graph.add_node("model", self._call_model)
        graph.add_node("tools", self._run_tools)
        graph.add_edge(START, "model")
        graph.add_conditional_edges("model", self._route_model, {"tools": "tools", "end": END})
        graph.add_edge("tools", "model")
        self._graph = graph.compile(checkpointer=InMemorySaver())

    def invoke(self, text: str, *, thread_id: str) -> AgentReply:
        final_state = self._graph.invoke(
            {
                "user_text": text,
                "model_turn": None,
                "pending_tool_results": [],
                "completed_tool_results": [],
                "tool_rounds": 0,
                "reply": None,
                "timings": [],
                "started_at": time.perf_counter(),
            },
            config={"configurable": {"thread_id": thread_id}},
        )
        reply = final_state.get("reply")
        if not isinstance(reply, AgentReply):
            raise RuntimeError("conversation graph produced no reply")
        return reply

    def _call_model(self, state: AgentState) -> dict[str, Any]:
        started = time.perf_counter()
        pending = state.get("pending_tool_results") or []
        previous_response_id = state.get("response_id")
        if pending:
            if not previous_response_id:
                raise RuntimeError("tool results have no preceding model response")
            turn = self.gateway.continue_with_tools(
                pending,
                previous_response_id=previous_response_id,
            )
        else:
            turn = self.gateway.respond(
                state["user_text"],
                previous_response_id=previous_response_id,
            )

        timings = [
            *(state.get("timings") or []),
            {
                "stage": "model_followup" if pending else "model_plan",
                "duration_ms": (time.perf_counter() - started) * 1000,
                "metrics": turn.metrics,
            },
        ]
        completed = list(state.get("completed_tool_results") or [])
        end_session = any(result.end_session for result in completed)
        reply: AgentReply | None = None
        if not turn.tool_calls:
            text = turn.text or "我没有得到可播报的结果。"
            reply = AgentReply(
                text, end_session=end_session, tool_results=tuple(completed), timings=tuple(timings)
            )
        elif state.get("tool_rounds", 0) >= self.max_tool_rounds:
            reply = AgentReply(
                "这个请求调用工具的次数过多，我先停在这里。",
                failed=True,
                end_session=end_session,
                tool_results=tuple(completed),
                timings=tuple(timings),
            )
        return {
            "response_id": turn.response_id,
            "model_turn": turn,
            "pending_tool_results": [],
            "reply": reply,
            "timings": timings,
        }

    @staticmethod
    def _route_model(state: AgentState) -> Literal["tools", "end"]:
        if state.get("reply") is not None:
            return "end"
        turn = state.get("model_turn")
        return "tools" if turn and turn.tool_calls else "end"

    def _run_tools(self, state: AgentState) -> dict[str, Any]:
        turn = state.get("model_turn")
        if turn is None:
            raise RuntimeError("tool node has no model turn")
        results = []
        timings = list(state.get("timings") or [])
        for call in turn.tool_calls:
            started = time.perf_counter()
            if self.progress is not None:
                self.progress(
                    {
                        "stage": "tool_dispatch",
                        "name": call.name,
                        "elapsed_ms": (started - state["started_at"]) * 1000,
                    }
                )
            results.append(self.tools.run(call))
            if self.progress is not None:
                self.progress(
                    {
                        "stage": "tool_complete",
                        "name": call.name,
                        "ok": bool(results[-1].output.get("ok")),
                        "elapsed_ms": (time.perf_counter() - state["started_at"]) * 1000,
                    }
                )
            timings.append(
                {
                    "stage": "tool",
                    "name": call.name,
                    "duration_ms": (time.perf_counter() - started) * 1000,
                }
            )
        completed = [*(state.get("completed_tool_results") or []), *results]
        return {
            "pending_tool_results": results,
            "completed_tool_results": completed,
            "tool_rounds": state.get("tool_rounds", 0) + 1,
            "timings": timings,
        }
