"""Independent ChatGPT OAuth credentials for the consumer Codex Responses backend."""

from __future__ import annotations

import base64
import json
import os
import tempfile
import threading
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

CODEX_OAUTH_CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
CODEX_OAUTH_ISSUER = "https://auth.openai.com"
CODEX_OAUTH_TOKEN_URL = f"{CODEX_OAUTH_ISSUER}/oauth/token"
CODEX_RESPONSES_BASE_URL = "https://chatgpt.com/backend-api/codex"
_REFRESH_SKEW_SECONDS = 120


def default_auth_path() -> Path:
    home = os.environ.get("HANPPIE_HOME", "").strip()
    return Path(home).expanduser() / "auth.json" if home else Path.home() / ".hanppie/auth.json"


class CodexOAuthManager:
    """Own device-code login, token refresh, and authenticated Responses clients."""

    def __init__(
        self,
        *,
        auth_path: Path | None = None,
        http_client_factory: Callable[..., Any] = httpx.Client,
        openai_factory: Callable[..., Any] | None = None,
        clock: Callable[[], float] = time.time,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        self.auth_path = auth_path or default_auth_path()
        self._http_client_factory = http_client_factory
        self._openai_factory = openai_factory
        self._clock = clock
        self._sleeper = sleeper
        self._lock = threading.Lock()
        self._client: Any | None = None
        self._client_token: str | None = None

    def login(
        self,
        *,
        notify: Callable[[str], None] = print,
        timeout_seconds: float = 900,
    ) -> Path:
        with self._http_client_factory(timeout=15.0) as client:
            device = client.post(
                f"{CODEX_OAUTH_ISSUER}/api/accounts/deviceauth/usercode",
                json={"client_id": CODEX_OAUTH_CLIENT_ID},
                headers={"Content-Type": "application/json"},
            )
            self._require_status(device, 200, "申请 Codex 设备授权码")
            device_data = device.json()
            user_code = str(device_data.get("user_code", "")).strip()
            device_auth_id = str(device_data.get("device_auth_id", "")).strip()
            if not user_code or not device_auth_id:
                raise RuntimeError("Codex 设备授权响应缺少必要字段")

            interval = max(1, int(device_data.get("interval", 5)))
            notify(f"打开 {CODEX_OAUTH_ISSUER}/codex/device")
            notify(f"输入授权码：{user_code}")
            notify("等待授权完成……")
            deadline = self._clock() + timeout_seconds
            exchange: dict[str, Any] | None = None
            while self._clock() < deadline:
                self._sleeper(interval)
                response = client.post(
                    f"{CODEX_OAUTH_ISSUER}/api/accounts/deviceauth/token",
                    json={"device_auth_id": device_auth_id, "user_code": user_code},
                    headers={"Content-Type": "application/json"},
                )
                if response.status_code == 200:
                    exchange = response.json()
                    break
                if response.status_code in {403, 404}:
                    continue
                self._require_status(response, 200, "轮询 Codex 设备授权")
            if exchange is None:
                raise TimeoutError("等待 Codex 设备授权超时")

            authorization_code = str(exchange.get("authorization_code", "")).strip()
            code_verifier = str(exchange.get("code_verifier", "")).strip()
            if not authorization_code or not code_verifier:
                raise RuntimeError("Codex 设备授权结果缺少交换凭据")
            token_response = client.post(
                CODEX_OAUTH_TOKEN_URL,
                data={
                    "grant_type": "authorization_code",
                    "code": authorization_code,
                    "redirect_uri": f"{CODEX_OAUTH_ISSUER}/deviceauth/callback",
                    "client_id": CODEX_OAUTH_CLIENT_ID,
                    "code_verifier": code_verifier,
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            self._require_status(token_response, 200, "交换 Codex OAuth token")
            tokens = self._normalize_tokens(token_response.json())

        self._save_tokens(tokens)
        return self.auth_path

    def logout(self) -> bool:
        self.close()
        try:
            self.auth_path.unlink()
        except FileNotFoundError:
            return False
        return True

    def client(self, *, force_refresh: bool = False) -> Any:
        with self._lock:
            tokens = self._load_tokens()
            if force_refresh or self._is_expiring(tokens):
                tokens = self._refresh(tokens)
            access_token = str(tokens.get("access_token", "")).strip()
            if not access_token:
                raise RuntimeError("尚未完成 Codex 授权；请先运行 `hanppie agent login`")
            if self._client is not None and self._client_token == access_token:
                return self._client
            self.close()
            factory = self._openai_factory
            if factory is None:
                from openai import OpenAI

                factory = OpenAI
            self._client = factory(
                api_key=access_token,
                base_url=CODEX_RESPONSES_BASE_URL,
                default_headers=self._request_headers(access_token),
                max_retries=0,
            )
            self._client_token = access_token
            return self._client

    def close(self) -> None:
        client = self._client
        self._client = None
        self._client_token = None
        close = getattr(client, "close", None)
        if callable(close):
            close()

    def _load_tokens(self) -> dict[str, Any]:
        try:
            payload = json.loads(self.auth_path.read_text(encoding="utf-8"))
        except FileNotFoundError as exc:
            raise RuntimeError("尚未完成 Codex 授权；请先运行 `hanppie agent login`") from exc
        except (OSError, json.JSONDecodeError) as exc:
            raise RuntimeError(f"无法读取 Codex 授权文件：{self.auth_path}") from exc
        tokens = payload.get("tokens") if isinstance(payload, dict) else None
        if not isinstance(tokens, dict) or not str(tokens.get("access_token", "")).strip():
            raise RuntimeError(f"Codex 授权文件无效：{self.auth_path}")
        return dict(tokens)

    def _refresh(self, tokens: dict[str, Any]) -> dict[str, Any]:
        refresh_token = str(tokens.get("refresh_token", "")).strip()
        if not refresh_token:
            raise RuntimeError("Codex refresh token 缺失；请重新运行 `hanppie agent login`")
        with self._http_client_factory(timeout=20.0) as client:
            response = client.post(
                CODEX_OAUTH_TOKEN_URL,
                data={
                    "grant_type": "refresh_token",
                    "refresh_token": refresh_token,
                    "client_id": CODEX_OAUTH_CLIENT_ID,
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
        self._require_status(response, 200, "刷新 Codex OAuth token")
        refreshed = self._normalize_tokens(response.json())
        if not refreshed.get("refresh_token"):
            refreshed["refresh_token"] = refresh_token
        self._save_tokens(refreshed)
        return refreshed

    def _save_tokens(self, tokens: dict[str, Any]) -> None:
        self.auth_path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            prefix=".auth-",
            suffix=".json",
            dir=self.auth_path.parent,
        )
        temporary_path = Path(temporary_name)
        try:
            if os.name != "nt":
                os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                json.dump(
                    {"version": 1, "provider": "openai-codex", "tokens": tokens},
                    stream,
                    ensure_ascii=False,
                    indent=2,
                )
                stream.write("\n")
            os.replace(temporary_path, self.auth_path)
        finally:
            temporary_path.unlink(missing_ok=True)

    def _normalize_tokens(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise RuntimeError("Codex token 响应不是 JSON 对象")
        access_token = str(payload.get("access_token", "")).strip()
        if not access_token:
            raise RuntimeError("Codex token 响应缺少 access_token")
        tokens: dict[str, Any] = {"access_token": access_token}
        refresh_token = str(payload.get("refresh_token", "")).strip()
        if refresh_token:
            tokens["refresh_token"] = refresh_token
        expires_in = payload.get("expires_in")
        if isinstance(expires_in, (int, float)) and expires_in > 0:
            tokens["expires_at"] = self._clock() + float(expires_in)
        return tokens

    def _is_expiring(self, tokens: dict[str, Any]) -> bool:
        expires_at = tokens.get("expires_at")
        if not isinstance(expires_at, (int, float)):
            expires_at = self._jwt_claims(str(tokens.get("access_token", ""))).get("exp")
        return isinstance(expires_at, (int, float)) and (
            float(expires_at) <= self._clock() + _REFRESH_SKEW_SECONDS
        )

    @classmethod
    def _request_headers(cls, access_token: str) -> dict[str, str]:
        headers = {
            "User-Agent": "codex_cli_rs/0.0.0 (Hanppie)",
            "originator": "codex_cli_rs",
        }
        auth_claims = cls._jwt_claims(access_token).get("https://api.openai.com/auth")
        if isinstance(auth_claims, dict):
            account_id = auth_claims.get("chatgpt_account_id")
            if isinstance(account_id, str) and account_id:
                headers["ChatGPT-Account-ID"] = account_id
        return headers

    @staticmethod
    def _jwt_claims(token: str) -> dict[str, Any]:
        try:
            encoded = token.split(".")[1]
            encoded += "=" * (-len(encoded) % 4)
            claims = json.loads(base64.urlsafe_b64decode(encoded))
        except (IndexError, ValueError, json.JSONDecodeError):
            return {}
        return claims if isinstance(claims, dict) else {}

    @staticmethod
    def _require_status(response: Any, expected: int, action: str) -> None:
        if response.status_code != expected:
            raise RuntimeError(f"{action}失败：HTTP {response.status_code}")
