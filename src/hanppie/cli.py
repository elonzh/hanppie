"""Command-line entry point for Hanppie."""

from __future__ import annotations

import argparse
import importlib
import os
import sys
from collections.abc import Sequence
from pathlib import Path

from hanppie import __version__

COMMANDS = {
    "sdk": ("hanppie.sdk_patch", "base"),
    "adb-enable": ("hanppie.adb_bootstrap", "lab"),
    "probe-official": ("hanppie.probes.official_info", "official"),
    "probe-connection": ("hanppie.probes.official_connection", "official"),
    "probe-telemetry": ("hanppie.probes.official_telemetry", "official"),
    "probe-led": ("hanppie.probes.official_led", "official"),
    "probe-camera": ("hanppie.probes.official_camera", "official"),
    "probe-lab": ("hanppie.probes.lab_bridge", "lab"),
    "probe-video": ("hanppie.probes.lab_video", "lab"),
}


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="hanppie",
        description="Preserve, inspect, and program a DJI RoboMaster S1.",
    )
    parser.add_argument("--version", action="version", version=__version__)
    parser.add_argument("command", choices=COMMANDS, help="operation to run")
    return parser


def _dependency_hint(extra: str) -> str:
    if extra == "official":
        return (
            "task sync:official, then set HANPPIE_OFFICIAL_SDK_PATH to a pinned "
            "DJI RoboMaster-SDK checkout"
        )
    if extra == "lab":
        return "uv sync --extra lab"
    return "uv sync"


def _add_official_sdk_path() -> None:
    checkout = os.environ.get("HANPPIE_OFFICIAL_SDK_PATH")
    if not checkout:
        return
    root = Path(checkout).expanduser().resolve()
    source = root / "src" if (root / "src" / "robomaster").is_dir() else root
    if str(source) not in sys.path:
        sys.path.insert(0, str(source))


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(argv) if argv is not None else sys.argv[1:]
    parser = build_parser()
    if not arguments or arguments[0] in {"-h", "--help", "--version"}:
        return int(parser.parse_args(arguments) is None)
    command, *remainder = arguments
    if command not in COMMANDS:
        parser.error(f"unknown command: {command}")
    module_name, extra = COMMANDS[command]
    if extra == "official":
        _add_official_sdk_path()
    try:
        module = importlib.import_module(module_name)
    except ModuleNotFoundError as exc:
        if exc.name in {"audioop", "robomaster", "robomaster_lab_sdk", "av", "cv2"}:
            parser.error(
                f"command {command!r} needs optional dependencies; "
                f"run `{_dependency_hint(extra)}` first ({exc})"
            )
        raise
    try:
        return int(module.main(remainder))
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        return 130
