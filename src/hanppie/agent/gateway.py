"""OpenAI Responses adapter for planning, tool calls, and camera understanding."""

from __future__ import annotations

import base64
import json
from pathlib import Path
from typing import Any

from hanppie.agent.model import ModelTurn, ToolCall, ToolResult


def observation_inputs(results: list[ToolResult]) -> list[dict[str, Any]]:
    """Attach trusted capture artifacts to the tool follow-up, not a third model call."""
    inputs = []
    for result in results:
        if result.name != "observe_surroundings" or not result.output.get("ok"):
            continue
        path = Path(result.output["image_path"])
        encoded = base64.b64encode(path.read_bytes()).decode("ascii")
        inputs.append(
            {
                "role": "user",
                "content": [
                    {
                        "type": "input_text",
                        "text": "这是刚才观察工具采集的前向画面，请据此回答原问题。",
                    },
                    {
                        "type": "input_image",
                        "image_url": f"data:image/jpeg;base64,{encoded}",
                        "detail": "auto",
                    },
                ],
            }
        )
    return inputs


TOOLS: list[dict[str, Any]] = [
    {
        "type": "function",
        "name": "execute_robot_python",
        "description": (
            "Execute one composed Python program against the persistent RoboMaster S1 facade. "
            "Use this for motion, gimbal, light, sound, capture, status, infrared, gel, and "
            "multi-step behavior. The program must assign a JSON-serializable value to result."
        ),
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": {
                "intent": {"type": "string", "description": "Brief Chinese intent summary."},
                "code": {"type": "string", "description": "Python using the robot facade."},
                "timeout_seconds": {
                    "type": ["number", "null"],
                    "minimum": 0.1,
                    "description": (
                        "Requested timeout for the complete sequence, or null for the default. "
                        "It must not exceed the execution context maximum."
                    ),
                },
            },
            "required": ["intent", "code", "timeout_seconds"],
            "additionalProperties": False,
        },
    },
    {
        "type": "function",
        "name": "observe_surroundings",
        "description": (
            "Capture the newest frame from the S1 forward camera and use vision to answer a "
            "question about what is currently visible. It does not provide a 360-degree scan."
        ),
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": {
                "question": {"type": "string", "description": "What to inspect in the frame."}
            },
            "required": ["question"],
            "additionalProperties": False,
        },
    },
    {
        "type": "function",
        "name": "sleep_session",
        "description": "End the active conversation and wait for the wake phrase again.",
        "strict": True,
        "parameters": {
            "type": "object",
            "properties": {},
            "required": [],
            "additionalProperties": False,
        },
    },
]


def build_instructions(robot_context: dict[str, object]) -> str:
    context = json.dumps(robot_context, ensure_ascii=False, default=str)
    return f"""你是运行在电脑上的 RoboMaster S1 对话智能体“小憨批”。
用简短、自然的中文回应，适合直接语音播报。你可以连续理解上下文。

工具策略：
- 普通问答直接回答；涉及机器人或当前画面的事实必须调用工具，不能假装已经执行。
- 所有机器人动作都通过 execute_robot_python 一次生成完整程序；不要把复合意图拆成每个动作一个工具。
- 用户问“附近有什么”“看到了什么”时调用 observe_surroundings。它只观察当前前向视野；如果用户明确要求环顾，可以先用机器人 Python 低速转动/转云台，再逐帧观察，但不要把单帧描述成 360 度结果。
- 用户要求休眠、结束对话或“退下”时调用 sleep_session。
- 工具失败时如实说明错误，不得声称完成。

生成机器人 Python 时：
- 可用名字只有 robot、time、sleep、checkpoint 和常见纯计算内置函数。
- 动作程序自行调用 robot.arm()，并用 try/finally 确保 robot.stop() 和 robot.disarm()。
- robot.disarm() 解除运动使能，不代表关闭灯光；保留用户要求的最终状态，不添加未要求的复原、关灯或其他操作。
- 仅在运动时长控制或用户明确要求等待时 sleep；瞬时状态设置不要附加演示性等待。代码保持简短，不输出重复的整份遥测。
- drive_speed 是速度命令，lease_seconds 是失联归零租约；持续时间乘速度只是开环估计，不能承诺精确距离或角度。
- 持续运动必须在循环中以不超过 0.2 秒的间隔重新发送 drive_speed，不能只发送一次后长时间 sleep；长动作应给工具提供足够但不超过上下文上限的 timeout_seconds。
- 将最终 JSON 可序列化值赋给 result；多阶段任务可用 checkpoint(name, **data)。
- 不导入模块，不访问电脑文件、环境变量、网络或私有属性。

当前执行上下文：{context}
"""


class OpenAIModelGateway:
    def __init__(
        self,
        client: Any,
        *,
        model: str,
        vision_model: str | None,
        robot_context: dict[str, object],
    ) -> None:
        self._client = client
        self.model = model
        self.vision_model = vision_model or model
        self.instructions = build_instructions(robot_context)

    def respond(
        self,
        user_text: str,
        *,
        previous_response_id: str | None,
    ) -> ModelTurn:
        arguments: dict[str, Any] = {
            "model": self.model,
            "instructions": self.instructions,
            "input": user_text,
            "tools": TOOLS,
            "parallel_tool_calls": False,
        }
        if previous_response_id:
            arguments["previous_response_id"] = previous_response_id
        return self._parse_response(self._client.responses.create(**arguments))

    def continue_with_tools(
        self,
        tool_results: list[ToolResult],
        *,
        previous_response_id: str,
    ) -> ModelTurn:
        tool_outputs = [
            {
                "type": "function_call_output",
                "call_id": result.call_id,
                "output": json.dumps(result.output, ensure_ascii=False, default=str),
            }
            for result in tool_results
        ]
        images = observation_inputs(tool_results)
        response = self._client.responses.create(
            model=self.vision_model if images else self.model,
            instructions=self.instructions,
            input=tool_outputs + images,
            previous_response_id=previous_response_id,
            tools=TOOLS,
            parallel_tool_calls=False,
        )
        return self._parse_response(response)

    def describe_image(self, image_path: Path, question: str) -> str:
        mime = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
        data = base64.b64encode(image_path.read_bytes()).decode("ascii")
        response = self._client.responses.create(
            model=self.vision_model,
            instructions=(
                "请客观、简短地描述 RoboMaster S1 当前前向相机画面。只说明画面中可见内容，"
                "不要推断画面外的周围环境；不确定的对象明确说不确定。"
            ),
            input=[
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": question},
                        {
                            "type": "input_image",
                            "image_url": f"data:{mime};base64,{data}",
                            "detail": "auto",
                        },
                    ],
                }
            ],
        )
        text = str(getattr(response, "output_text", "")).strip()
        if not text:
            raise RuntimeError("vision model returned no description")
        return text

    @staticmethod
    def _parse_response(response: Any) -> ModelTurn:
        calls: list[ToolCall] = []
        for item in getattr(response, "output", ()):
            if getattr(item, "type", None) != "function_call":
                continue
            raw_arguments = getattr(item, "arguments", "{}")
            try:
                arguments = (
                    raw_arguments
                    if isinstance(raw_arguments, dict)
                    else json.loads(raw_arguments or "{}")
                )
            except json.JSONDecodeError as exc:
                raise RuntimeError("model returned invalid tool arguments") from exc
            if not isinstance(arguments, dict):
                raise RuntimeError("model returned non-object tool arguments")
            calls.append(
                ToolCall(
                    name=str(getattr(item, "name", "")),
                    call_id=str(getattr(item, "call_id", "")),
                    arguments=arguments,
                )
            )
        response_id = str(getattr(response, "id", ""))
        if not response_id:
            raise RuntimeError("model response has no id")
        return ModelTurn(
            response_id=response_id,
            text=str(getattr(response, "output_text", "")).strip(),
            tool_calls=tuple(calls),
        )
