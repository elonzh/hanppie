from __future__ import annotations

from pathlib import Path

from typer.testing import CliRunner

from hanppie import cli
from hanppie.diagnosis.model import validate_safety

runner = CliRunner()


def test_version_and_help_only_expose_diag() -> None:
    version = runner.invoke(cli.app, ["--version"])
    help_result = runner.invoke(cli.app, ["--help"])

    assert version.exit_code == 0
    assert version.stdout.strip() == "0.1.0"
    assert help_result.exit_code == 0
    assert "diag" in help_result.stdout
    assert "survey" not in help_result.stdout
    assert "probe-" not in help_result.stdout


def test_removed_commands_are_not_accepted() -> None:
    for command in ("survey", "sdk", "adb-enable", "probe-official"):
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
