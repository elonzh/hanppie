from __future__ import annotations

import json
from pathlib import Path

from typer.testing import CliRunner

from hanppie import cli
from hanppie.diagnosis.model import validate_safety

runner = CliRunner()


def test_version_and_help_expose_diag_and_mcp() -> None:
    version = runner.invoke(cli.app, ["--version"])
    help_result = runner.invoke(cli.app, ["--help"])

    assert version.exit_code == 0
    assert version.stdout.strip() == "0.1.0"
    assert help_result.exit_code == 0
    assert "diag" in help_result.stdout
    assert "mcp" in help_result.stdout
    assert "robot" not in help_result.stdout
    assert "survey" not in help_result.stdout
    assert "probe-" not in help_result.stdout


def test_removed_commands_are_not_accepted() -> None:
    for command in ("survey", "sdk", "adb-enable", "probe-official", "robot"):
        result = runner.invoke(cli.app, [command])
        assert result.exit_code == 2
        assert "No such command" in result.output


def test_diag_noninteractive_dispatch_and_safety(monkeypatch, tmp_path: Path) -> None:
    captured = []

    def fake_run(config, *, console):
        validate_safety(config)
        captured.append((config, console))
        return 0, tmp_path / "report.md", tmp_path / "events.jsonl"

    monkeypatch.setattr(cli, "run_diagnosis", fake_run)

    result = runner.invoke(
        cli.app,
        [
            "diag",
            "--no-interactive",
            "--check",
            "app,gel",
            "--allow-gel",
            "--output-dir",
            str(tmp_path),
        ],
    )
    blocked = runner.invoke(
        cli.app,
        ["diag", "--no-interactive", "--check", "chassis"],
    )

    assert result.exit_code == 0
    assert captured[0][0].checks == ("app", "gel")
    assert captured[0][0].allow_gel
    assert blocked.exit_code == 2
    assert "--allow-motion" in blocked.output


def test_diag_lists_checks_and_rejects_unknown() -> None:
    listed = runner.invoke(cli.app, ["diag", "--list"])
    invalid = runner.invoke(
        cli.app,
        ["diag", "--no-interactive", "--check", "unknown"],
    )

    assert listed.exit_code == 0
    assert "chassis" in listed.output
    assert "speaker" in listed.output
    assert "microphone" in listed.output
    assert "muzzle" in listed.output
    assert invalid.exit_code == 2
    assert "unknown" in invalid.output


def test_main_returns_exit_status() -> None:
    assert cli.main(["--version"]) == 0


def test_mcp_install_writes_shared_codex_config(tmp_path: Path) -> None:
    result = runner.invoke(
        cli.app,
        ["mcp", "install", "--codex-home", str(tmp_path / "codex")],
    )
    payload = json.loads(result.stdout)

    assert result.exit_code == 0
    assert payload["changed"] is True
    assert Path(payload["config_path"]).exists()


def test_mcp_install_is_idempotent_and_can_target_project(tmp_path: Path) -> None:
    (tmp_path / "pyproject.toml").write_text("[project]\nname='demo'\n", encoding="utf-8")
    arguments = ["mcp", "install", "--scope", "project", "--project-dir", str(tmp_path)]

    first = runner.invoke(cli.app, arguments)
    second = runner.invoke(cli.app, arguments)

    assert first.exit_code == 0
    assert json.loads(first.stdout)["changed"] is True
    assert second.exit_code == 0
    assert json.loads(second.stdout)["changed"] is False
    assert (tmp_path / ".codex" / "config.toml").exists()


def test_mcp_has_no_action_permission_options() -> None:
    result = runner.invoke(cli.app, ["mcp", "serve", "--help"], env={"COLUMNS": "200"})

    assert result.exit_code == 0
    assert "--allow-motion" not in result.output
    assert "--allow-infrared" not in result.output
    assert "--allow-gel" not in result.output
    assert "--max-lease-seconds" not in result.output
