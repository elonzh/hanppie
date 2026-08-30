#!/usr/bin/env python3
"""Upload a minimal Lab program that enables ADB on a rooted RoboMaster S1."""

from __future__ import annotations

import argparse
import time
from importlib.resources import files
from pathlib import Path

from robomaster_lab_sdk.program import (
    build_lab_bridge_dsp,
    upload_lab_dsp,
)
from robomaster_lab_sdk.robot import Robot


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Enable S1 ADB through the App-compatible Lab transport."
    )
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--appid", required=True)
    parser.add_argument(
        "--payload",
        type=Path,
        help="advanced: use a custom robot-side Python payload",
    )
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--settle-seconds", type=float, default=5.0)
    parser.add_argument("--debug", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args(argv)


def upload_program(s1: Robot, python_code: str) -> str:
    dsp, identity = build_lab_bridge_dsp(python_code=python_code)
    byte_count = len(dsp.encode("utf-8"))

    s1.send_duss(0x02, 0x09, 0x40, 0x3F, 0x4C, b"\x00")
    time.sleep(0.02)
    s1.send_lab_metadata(identity.guid, identity.sign, identity.full_marker)
    time.sleep(0.02)
    s1.send_lab_guid_metadata(identity.guid, identity.guid_marker)
    time.sleep(0.02)
    s1.send_lab_upload_size(byte_count)
    time.sleep(0.02)

    digest = upload_lab_dsp(s1.robot_ip, dsp, timeout=10.0)

    # start_lab_program() needs the identity associated with the uploaded digest.
    # LAB-SDK 0.1.0 does not yet expose a public custom-program upload method.
    s1._last_dsp_md5 = digest
    s1._last_guid = identity.guid
    s1._last_sign = identity.sign
    s1._last_full_marker = identity.full_marker
    s1._last_guid_marker = identity.guid_marker
    s1._lab_program_registered = False

    print(f"uploaded Lab payload: bytes={byte_count} md5={digest}")
    return digest


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    if args.payload:
        payload_source = args.payload.read_text(encoding="utf-8")
        payload_name = str(args.payload)
    else:
        payload = files("hanppie.payloads").joinpath("enable_adb_standalone.py.txt")
        payload_source = payload.read_text(encoding="utf-8")
        payload_name = "hanppie:enable_adb_standalone.py.txt"
    python_code = payload_source
    dsp, identity = build_lab_bridge_dsp(python_code=python_code)
    print(
        "prepared Lab payload: "
        f"source={payload_name} bytes={len(dsp.encode('utf-8'))} "
        f"guid={identity.guid} sign={identity.sign}"
    )
    if args.dry_run:
        return 0

    s1 = Robot(robot_ip=args.robot_ip, appid=args.appid, debug=args.debug)
    initialized = False
    program_started = False
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", timeout=args.timeout)
        if not initialized:
            raise RuntimeError("S1 App-compatible connection was not initialized")
        print(f"connected: {s1.info}")

        s1.enter_lab()
        print("entered Lab mode")

        digest = upload_program(s1, python_code)
        s1.start_lab_program(digest)
        program_started = True
        print("started ADB enable payload")
        time.sleep(args.settle_seconds)
        print("ADB enable payload settle period completed")
        return 0
    finally:
        if initialized:
            if program_started:
                try:
                    s1.stop_lab_program()
                    print("stopped temporary Lab program")
                except Exception as exc:
                    print(f"warning: could not stop temporary Lab program: {exc}")
            s1.close()
            print("closed App-compatible connection")


if __name__ == "__main__":
    raise SystemExit(main())
