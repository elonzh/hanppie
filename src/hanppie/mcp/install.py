"""Idempotent cross-platform Codex MCP configuration."""

from __future__ import annotations

import os
import stat
import sys
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Literal

import tomlkit
from tomlkit.items import Table

Scope = Literal["user", "project"]


def build_server_entry(
    serve_args: Sequence[str],
    *,
    executable: str | None = None,
    tool_timeout_seconds: float = 65.0,
) -> dict[str, object]:
    """Build a shell-independent STDIO entry for the current Python environment."""

    python = executable or str(Path(sys.executable).absolute())
    return {
        "command": python,
        "args": ["-m", "hanppie", "mcp", "serve", *serve_args],
        "startup_timeout_sec": 15,
        "tool_timeout_sec": max(1, int(tool_timeout_seconds)),
        "default_tools_approval_mode": "writes",
    }


def resolve_codex_config_path(
    scope: Scope,
    *,
    project_dir: Path | None = None,
    codex_home: Path | None = None,
    environment: Mapping[str, str] = os.environ,
    user_home: Path | None = None,
) -> Path:
    """Resolve the shared Codex config on Windows, macOS, Linux, or WSL."""

    if scope == "project":
        root = _find_project_root((project_dir or Path.cwd()).resolve())
        return root / ".codex" / "config.toml"
    if scope != "user":
        raise ValueError("scope must be 'user' or 'project'")
    if codex_home is not None:
        root = codex_home.expanduser().resolve()
    elif environment.get("CODEX_HOME"):
        root = Path(environment["CODEX_HOME"]).expanduser().resolve()
    else:
        root = (user_home or Path.home()).expanduser().resolve() / ".codex"
    return root / "config.toml"


def install_codex_server(
    entry: Mapping[str, object],
    *,
    scope: Scope = "user",
    project_dir: Path | None = None,
    codex_home: Path | None = None,
    replace: bool = False,
) -> dict[str, object]:
    """Add or update only ``mcp_servers.hanppie`` and preserve the rest of the file."""

    path = resolve_codex_config_path(
        scope,
        project_dir=project_dir,
        codex_home=codex_home,
    )
    try:
        source = path.read_text(encoding="utf-8") if path.exists() else ""
        document = tomlkit.parse(source)
    except (OSError, tomlkit.exceptions.ParseError) as exc:
        raise ValueError(f"cannot read Codex config {path}: {exc}") from exc

    servers = document.get("mcp_servers")
    if servers is None:
        document["mcp_servers"] = tomlkit.table()
        servers = document["mcp_servers"]
    if not isinstance(servers, Table):
        raise ValueError(f"mcp_servers in {path} is not a TOML table")

    desired = dict(entry)
    existing = servers.get("hanppie")
    if existing is not None:
        current = existing.unwrap() if hasattr(existing, "unwrap") else dict(existing)
        if current == desired:
            return {
                "ok": True,
                "changed": False,
                "scope": scope,
                "config_path": str(path),
                "server": "hanppie",
            }
        if not replace:
            raise ValueError(
                "mcp_servers.hanppie already exists with different settings; "
                "rerun with --replace to update only that table"
            )

    server_table = tomlkit.table()
    for key, value in desired.items():
        server_table.add(key, value)
    servers["hanppie"] = server_table
    _write_atomic(path, tomlkit.dumps(document))
    return {
        "ok": True,
        "changed": True,
        "scope": scope,
        "config_path": str(path),
        "server": "hanppie",
    }


def _find_project_root(start: Path) -> Path:
    if start.is_file():
        start = start.parent
    for candidate in (start, *start.parents):
        if (candidate / ".git").exists() or (candidate / "pyproject.toml").exists():
            return candidate
    return start


def _write_atomic(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    previous_mode = stat.S_IMODE(path.stat().st_mode) if path.exists() else 0o600
    file_descriptor, temporary_name = tempfile.mkstemp(
        prefix=".config.toml.",
        dir=path.parent,
        text=True,
    )
    temporary_path = Path(temporary_name)
    try:
        os.chmod(temporary_path, previous_mode)
        with os.fdopen(file_descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(contents)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
    except BaseException:
        try:
            os.close(file_descriptor)
        except OSError:
            pass
        temporary_path.unlink(missing_ok=True)
        raise
