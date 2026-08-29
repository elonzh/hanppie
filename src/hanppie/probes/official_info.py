#!/usr/bin/env python3
"""Exercise read-only RoboMaster S1 APIs through DJI's official Python SDK."""

from __future__ import annotations

import argparse
import time

from hanppie.media_codec import install_media_codec_compat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Initialize DJI's SDK and sample S1 metadata and telemetry."
    )
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--sn", default=None)
    parser.add_argument("--sample-seconds", type=float, default=2.0)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    install_media_codec_compat()
    from robomaster import config, robot

    args = parse_args(argv)
    config.ROBOT_IP_STR = args.robot_ip
    s1 = robot.Robot()
    initialized = False
    battery_subscribed = False
    attitude_subscribed = False
    battery_samples: list[int] = []
    attitude_samples: list[tuple[float, float, float]] = []
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", sn=args.sn)
        if not initialized:
            raise RuntimeError("DJI Robot.initialize() returned false")
        print("official_robot_initialized=True")
        print(f"version={s1.get_version()}")
        print(f"serial_number={s1.get_sn()}")
        print(f"robot_mode={s1.get_robot_mode()}")

        battery_subscribed = s1.battery.sub_battery_info(
            freq=5, callback=lambda value: battery_samples.append(int(value))
        )
        attitude_subscribed = s1.chassis.sub_attitude(
            freq=5, callback=lambda value: attitude_samples.append(tuple(value))
        )
        time.sleep(max(0.0, args.sample_seconds))
        print(f"battery_samples={len(battery_samples)} last={battery_samples[-1:] or None}")
        print(f"attitude_samples={len(attitude_samples)} last={attitude_samples[-1:] or None}")
        if not battery_samples or not attitude_samples:
            raise RuntimeError("official SDK returned incomplete telemetry")
        return 0
    finally:
        if battery_subscribed:
            s1.battery.unsub_battery_info()
        if attitude_subscribed:
            s1.chassis.unsub_attitude()
        if initialized:
            s1.close()
            print("official_robot_closed=True")


if __name__ == "__main__":
    raise SystemExit(main())
