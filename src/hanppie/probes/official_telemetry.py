#!/usr/bin/env python3
"""Sample non-actuating telemetry through DJI's official RoboMaster SDK."""

from __future__ import annotations

import argparse
import copy
import time
from collections.abc import Callable

from hanppie.media_codec import install_media_codec_compat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--sn", required=True)
    parser.add_argument("--sample-seconds", type=float, default=4.0)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    install_media_codec_compat()
    from robomaster import config, robot

    args = parse_args(argv)
    config.ROBOT_IP_STR = args.robot_ip
    s1 = robot.Robot()
    initialized = False
    subscriptions: list[tuple[str, Callable[[], bool]]] = []
    samples: dict[str, list[object]] = {}

    def callback_for(name: str) -> Callable[[object], None]:
        samples[name] = []

        def record(value: object) -> None:
            samples[name].append(copy.deepcopy(value))

        return record

    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", sn=args.sn)
        if not initialized:
            raise RuntimeError("DJI Robot.initialize() returned false")

        probes = [
            ("battery", s1.battery.sub_battery_info, s1.battery.unsub_battery_info, {}),
            ("attitude", s1.chassis.sub_attitude, s1.chassis.unsub_attitude, {}),
            ("imu", s1.chassis.sub_imu, s1.chassis.unsub_imu, {}),
            ("esc", s1.chassis.sub_esc, s1.chassis.unsub_esc, {}),
            ("status", s1.chassis.sub_status, s1.chassis.unsub_status, {}),
            ("position", s1.chassis.sub_position, s1.chassis.unsub_position, {"cs": 1}),
            ("velocity", s1.chassis.sub_velocity, s1.chassis.unsub_velocity, {}),
            ("gimbal_angle", s1.gimbal.sub_angle, s1.gimbal.unsub_angle, {}),
        ]

        for name, subscribe, unsubscribe, kwargs in probes:
            ok = subscribe(freq=5, callback=callback_for(name), **kwargs)
            print(f"subscribe_{name}={ok}")
            if ok:
                subscriptions.append((name, unsubscribe))

        time.sleep(max(0.0, args.sample_seconds))
        for name, values in samples.items():
            print(
                f"{name}: samples={len(values)} first={values[:1] or None} last={values[-1:] or None}"
            )
        return 0
    finally:
        for name, unsubscribe in reversed(subscriptions):
            try:
                print(f"unsubscribe_{name}={unsubscribe()}")
            except Exception as exc:
                print(f"unsubscribe_{name}=error:{exc}")
        if initialized:
            s1.close()
            print("official_robot_closed=True")


if __name__ == "__main__":
    raise SystemExit(main())
