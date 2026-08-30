#!/usr/bin/env python3
"""Probe the Hanppie S1 Lab bridge without issuing a motion command."""

from __future__ import annotations

import argparse
import time

from hanppie.lab import LabRobot


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify S1 Lab program execution and telemetry return path."
    )
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--appid", required=True)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--debug", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    s1 = LabRobot(robot_ip=args.robot_ip, appid=args.appid, debug=args.debug)
    initialized = False
    program_started = False
    bridge_started = False
    attitude_samples: list[tuple[object, ...]] = []
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", timeout=args.timeout)
        if not initialized:
            raise RuntimeError("S1 App-compatible connection was not initialized")
        print(f"connected: {s1.info}")

        s1.enter_lab()
        print("entered Lab mode")
        digest = s1.upload_lab_bridge()
        print(f"uploaded Hanppie Lab bridge: md5={digest}")
        s1.start_lab_program()
        program_started = True
        print("started Hanppie Lab bridge program")
        s1.start_lab_bridge()
        bridge_started = True
        print(f"host bridge ready: worker_threads={s1.bridge.worker_threads}")

        s1.chassis.sub_attitude(
            freq=5, callback=lambda value: attitude_samples.append(tuple(value))
        )
        time.sleep(1.0)
        print(f"attitude samples: count={len(attitude_samples)} last={attitude_samples[-1:]}")
        if not attitude_samples:
            raise RuntimeError("Lab bridge returned no chassis attitude samples")
        return 0
    finally:
        if bridge_started:
            s1.stop_lab_bridge()
            print("stopped Host bridge")
        if program_started:
            try:
                s1.stop_lab_program()
                print("stopped robot Lab program")
            except Exception as exc:
                print(f"warning: could not stop robot Lab program: {exc}")
        if initialized:
            s1.close()
            print("closed App-compatible connection")


if __name__ == "__main__":
    raise SystemExit(main())
