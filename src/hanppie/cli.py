"""Command-line entry point for Hanppie."""

from __future__ import annotations

import argparse
import importlib
import sys
from collections.abc import Sequence

from hanppie import __version__

COMMANDS = {
    "sdk": ("hanppie.sdk_patch", "base"),
    "adb-enable": ("hanppie.adb_bootstrap", "base"),
    "probe-official": ("hanppie.probes.official_info", "base"),
    "probe-connection": ("hanppie.probes.official_connection", "base"),
    "probe-telemetry": ("hanppie.probes.official_telemetry", "base"),
    "probe-led": ("hanppie.probes.official_led", "base"),
    "probe-camera": ("hanppie.probes.official_camera", "base"),
    "probe-lab": ("hanppie.probes.lab_bridge", "base"),
    "probe-video": ("hanppie.probes.lab_video", "base"),
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
    del extra
    return "run `uv sync`"


def main(argv: Sequence[str] | None = None) -> int:
    arguments = list(argv) if argv is not None else sys.argv[1:]
    parser = build_parser()
    if not arguments or arguments[0] in {"-h", "--help", "--version"}:
        return int(parser.parse_args(arguments) is None)
    command, *remainder = arguments
    if command not in COMMANDS:
        parser.error(f"unknown command: {command}")
    module_name, extra = COMMANDS[command]
    try:
        module = importlib.import_module(module_name)
    except ModuleNotFoundError as exc:
        if exc.name in {"audioop", "robomaster", "av", "cv2"}:
            parser.error(
                f"command {command!r} needs optional dependencies; "
                f"{_dependency_hint(extra)} ({exc})"
            )
        raise
    try:
        return int(module.main(remainder))
    except KeyboardInterrupt:
        print("interrupted", file=sys.stderr)
        return 130
