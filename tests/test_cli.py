from __future__ import annotations

import sys
import types

import pytest

from hanppie import cli


def test_version(capsys: pytest.CaptureFixture[str]) -> None:
    with pytest.raises(SystemExit, match="0"):
        cli.main(["--version"])
    assert capsys.readouterr().out.strip() == "0.1.0"


def test_dispatches_command(monkeypatch: pytest.MonkeyPatch) -> None:
    module = types.SimpleNamespace(main=lambda arguments: 7 if arguments == ["value"] else 1)
    monkeypatch.setitem(sys.modules, "hanppie.test_command", module)
    monkeypatch.setitem(cli.COMMANDS, "test-command", ("hanppie.test_command", "base"))

    assert cli.main(["test-command", "value"]) == 7


def test_missing_optional_dependency_shows_install_hint(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    def missing(_name: str) -> None:
        raise ModuleNotFoundError("missing", name="robomaster")

    monkeypatch.setattr(cli.importlib, "import_module", missing)

    with pytest.raises(SystemExit, match="2"):
        cli.main(["probe-official"])

    assert "uv sync" in capsys.readouterr().err


def test_keyboard_interrupt_returns_shell_status(monkeypatch: pytest.MonkeyPatch) -> None:
    def interrupt(_arguments: list[str]) -> int:
        raise KeyboardInterrupt

    module = types.SimpleNamespace(main=interrupt)
    monkeypatch.setitem(sys.modules, "hanppie.test_interrupt", module)
    monkeypatch.setitem(cli.COMMANDS, "test-interrupt", ("hanppie.test_interrupt", "base"))

    assert cli.main(["test-interrupt"]) == 130
