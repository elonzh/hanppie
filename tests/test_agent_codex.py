from __future__ import annotations

import base64
import json
import os
from pathlib import Path
from types import SimpleNamespace

import pytest

from hanppie.agent.codex import CodexModelGateway
from hanppie.agent.codex_auth import CodexOAuthManager
from hanppie.agent.model import ToolResult


class HTTPResponse:
    def __init__(self, status_code: int, payload: dict) -> None:
        self.status_code = status_code
        self._payload = payload

    def json(self) -> dict:
        return self._payload


class HTTPClient:
    def __init__(self, responses: list[HTTPResponse]) -> None:
        self.responses = responses
        self.calls: list[tuple[str, dict]] = []

    def __enter__(self):
        return self

    def __exit__(self, *args) -> None:
        pass

    def post(self, url: str, **kwargs) -> HTTPResponse:
        self.calls.append((url, kwargs))
        return self.responses.pop(0)


class HTTPFactory:
    def __init__(self, responses: list[HTTPResponse]) -> None:
        self.client = HTTPClient(responses)

    def __call__(self, **kwargs) -> HTTPClient:
        return self.client


def _jwt(*, account_id: str = "account-1", exp: int = 9_999_999_999) -> str:
    def encode(payload: dict) -> str:
        return base64.urlsafe_b64encode(json.dumps(payload).encode()).rstrip(b"=").decode()

    return (
        f"{encode({'alg': 'none'})}."
        f"{encode({'exp': exp, 'https://api.openai.com/auth': {'chatgpt_account_id': account_id}})}.x"
    )


def test_codex_oauth_device_login_is_independent_of_codex_cli(tmp_path: Path) -> None:
    access_token = _jwt()
    factory = HTTPFactory(
        [
            HTTPResponse(
                200,
                {"user_code": "ABCD-EFGH", "device_auth_id": "device-1", "interval": 1},
            ),
            HTTPResponse(403, {}),
            HTTPResponse(200, {"authorization_code": "code", "code_verifier": "verifier"}),
            HTTPResponse(
                200,
                {"access_token": access_token, "refresh_token": "refresh-1", "expires_in": 3600},
            ),
        ]
    )
    messages: list[str] = []
    auth_path = tmp_path / "auth.json"
    manager = CodexOAuthManager(
        auth_path=auth_path,
        http_client_factory=factory,
        clock=lambda: 1000,
        sleeper=lambda _: None,
    )

    saved = manager.login(notify=messages.append)

    payload = json.loads(auth_path.read_text(encoding="utf-8"))
    assert saved == auth_path
    assert payload["provider"] == "openai-codex"
    assert payload["tokens"]["access_token"] == access_token
    assert payload["tokens"]["expires_at"] == 4600
    assert "ABCD-EFGH" in messages[1]
    assert all("codex" not in call[0].split("/")[-1] for call in factory.client.calls)
    if os.name != "nt":
        assert auth_path.stat().st_mode & 0o777 == 0o600


def test_codex_oauth_refreshes_and_builds_direct_responses_client(tmp_path: Path) -> None:
    old_token = _jwt(account_id="old", exp=900)
    new_token = _jwt(account_id="new", exp=5000)
    auth_path = tmp_path / "auth.json"
    auth_path.write_text(
        json.dumps(
            {
                "tokens": {
                    "access_token": old_token,
                    "refresh_token": "refresh-old",
                    "expires_at": 900,
                }
            }
        ),
        encoding="utf-8",
    )
    http = HTTPFactory(
        [HTTPResponse(200, {"access_token": new_token, "refresh_token": "refresh-new"})]
    )
    created: list[dict] = []

    def openai_factory(**kwargs):
        created.append(kwargs)
        return SimpleNamespace(close=lambda: None)

    manager = CodexOAuthManager(
        auth_path=auth_path,
        http_client_factory=http,
        openai_factory=openai_factory,
        clock=lambda: 1000,
    )

    manager.client()

    assert created[0]["api_key"] == new_token
    assert created[0]["base_url"] == "https://chatgpt.com/backend-api/codex"
    headers = created[0]["default_headers"]
    assert headers["originator"] == "codex_cli_rs"
    assert headers["ChatGPT-Account-ID"] == "new"
    saved = json.loads(auth_path.read_text(encoding="utf-8"))["tokens"]
    assert saved["refresh_token"] == "refresh-new"


def test_codex_oauth_requires_login_and_logout_is_local(tmp_path: Path) -> None:
    manager = CodexOAuthManager(auth_path=tmp_path / "auth.json")
    with pytest.raises(RuntimeError, match="hanppie agent login"):
        manager.client()

    manager.auth_path.write_text('{"tokens":{"access_token":"token"}}', encoding="utf-8")
    assert manager.logout() is True
    assert manager.logout() is False


class OutputItem(SimpleNamespace):
    def model_dump(self, *, exclude_none: bool) -> dict:
        del exclude_none
        return dict(vars(self))


def _event(event_type: str, **kwargs) -> SimpleNamespace:
    return SimpleNamespace(type=event_type, **kwargs)


def _stream(*, text: str = "", item: OutputItem | None = None, completed: bool = True):
    events = [_event("response.created", response=SimpleNamespace(id="remote-response"))]
    if text:
        events.append(_event("response.output_text.delta", delta=text))
    if item is not None:
        events.append(_event("response.output_item.done", item=item))
    if completed:
        events.append(_event("response.completed", response=SimpleNamespace(id="remote-response")))
    return events


class FakeResponses:
    def __init__(self, streams: list[list[SimpleNamespace]]) -> None:
        self.streams = streams
        self.calls: list[dict] = []

    def create(self, **kwargs):
        self.calls.append(kwargs)
        return self.streams.pop(0)


class FakeOAuth:
    def __init__(self, responses: FakeResponses) -> None:
        self.responses = responses
        self.force_refreshes: list[bool] = []
        self.closed = False

    def client(self, *, force_refresh: bool = False):
        self.force_refreshes.append(force_refresh)
        return SimpleNamespace(responses=self.responses)

    def close(self) -> None:
        self.closed = True


def test_observation_merges_vision_followup_without_replaying_images(tmp_path):
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    responses = FakeResponses(
        [
            _stream(
                item=OutputItem(
                    type="function_call",
                    name="observe_surroundings",
                    call_id="camera",
                    arguments='{"question":"看什么"}',
                )
            ),
            _stream(
                text="前面有桌子", item=OutputItem(type="message", role="assistant", content=[])
            ),
            _stream(text="刚才看到桌子"),
        ]
    )
    gateway = CodexModelGateway(
        FakeOAuth(responses), model="fast-text", vision_model="vision", robot_context={}
    )
    first = gateway.respond("看一下", previous_response_id=None)
    reply = gateway.continue_with_tools(
        [ToolResult("camera", "observe_surroundings", {"ok": True, "image_path": str(image)})],
        previous_response_id=first.response_id,
    )
    assert reply.text == "前面有桌子"
    assert len(responses.calls) == 2
    assert responses.calls[1]["model"] == "vision"
    assert "input_image" in json.dumps(responses.calls[1]["input"])
    gateway.respond("刚才看到了什么", previous_response_id=first.response_id)
    assert responses.calls[2]["model"] == "fast-text"
    assert "input_image" not in json.dumps(responses.calls[2]["input"])


@pytest.mark.parametrize("completed", [True, False])
def test_stream_is_closed_and_effort_and_timeout_are_explicit(completed):
    class Stream:
        closed = False

        def __iter__(self):
            yield from _stream(text="你好", completed=completed)
            if completed:
                pytest.fail("must stop reading after response.completed")

        def close(self):
            self.closed = True

    stream = Stream()
    responses = FakeResponses([stream])
    gateway = CodexModelGateway(
        FakeOAuth(responses),
        model="test",
        vision_model="vision",
        robot_context={},
        reasoning_effort="minimal",
        timeout_seconds=12,
    )
    if completed:
        reply = gateway.respond("你好", previous_response_id=None)
        assert reply.metrics["first_output_delta_ms"] >= 0
    else:
        with pytest.raises(RuntimeError, match="完成事件"):
            gateway.respond("你好", previous_response_id=None)
    assert responses.calls[0]["reasoning"] == {"effort": "minimal"}
    assert responses.calls[0]["timeout"] == 12
    assert stream.closed


def test_codex_gateway_streams_directly_and_replays_local_history(tmp_path: Path) -> None:
    function_call = OutputItem(
        type="function_call",
        name="execute_robot_python",
        call_id="call-1",
        arguments=json.dumps(
            {"intent": "前进", "code": "result = robot.status()", "timeout_seconds": None}
        ),
    )
    message = OutputItem(type="message", role="assistant", content=[])
    responses = FakeResponses(
        [
            _stream(item=function_call),
            _stream(text="已经完成", item=message),
            _stream(text="前方有桌子", item=message),
        ]
    )
    oauth = FakeOAuth(responses)
    gateway = CodexModelGateway(
        oauth,  # type: ignore[arg-type]
        model="gpt-5.6-sol",
        vision_model=None,
        robot_context={},
    )

    first = gateway.respond("前进", previous_response_id=None)
    final = gateway.continue_with_tools(
        [ToolResult("call-1", "execute_robot_python", {"ok": True})],
        previous_response_id=first.response_id,
    )
    image = tmp_path / "frame.jpg"
    image.write_bytes(b"jpeg")
    description = gateway.describe_image(image, "看到了什么")
    gateway.close()

    assert first.response_id.startswith("codex-")
    assert first.tool_calls[0].arguments["intent"] == "前进"
    assert final.response_id == first.response_id
    assert final.text == "已经完成"
    assert responses.calls[0]["stream"] is True
    assert responses.calls[0]["store"] is False
    assert responses.calls[0]["model"] == "gpt-5.6-sol"
    assert responses.calls[1]["input"][1]["type"] == "function_call"
    assert responses.calls[1]["input"][2]["type"] == "function_call_output"
    assert description == "前方有桌子"
    assert responses.calls[2]["input"][0]["content"][1]["image_url"].startswith(
        "data:image/jpeg;base64,"
    )
    assert oauth.closed is True


def test_codex_gateway_retries_one_unauthorized_request_after_refresh() -> None:
    class UnauthorizedError(Exception):
        status_code = 401

    class RetryResponses(FakeResponses):
        def create(self, **kwargs):
            self.calls.append(kwargs)
            if len(self.calls) == 1:
                raise UnauthorizedError
            return _stream(text="好了", item=OutputItem(type="message", content=[]))

    responses = RetryResponses([])
    oauth = FakeOAuth(responses)
    gateway = CodexModelGateway(
        oauth,  # type: ignore[arg-type]
        model="model",
        vision_model=None,
        robot_context={},
    )

    assert gateway.respond("你好", previous_response_id=None).text == "好了"
    assert oauth.force_refreshes == [False, True]


def test_codex_gateway_rejects_unknown_conversation_and_incomplete_stream() -> None:
    responses = FakeResponses([_stream(completed=False)])
    gateway = CodexModelGateway(
        FakeOAuth(responses),  # type: ignore[arg-type]
        model="model",
        vision_model=None,
        robot_context={},
    )
    with pytest.raises(RuntimeError, match="上下文已经失效"):
        gateway.respond("继续", previous_response_id="missing")
    with pytest.raises(RuntimeError, match="没有完成事件"):
        gateway.respond("开始", previous_response_id=None)
