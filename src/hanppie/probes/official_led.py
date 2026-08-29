#!/usr/bin/env python3
"""Verify an official-SDK hardware command without mechanical movement."""

from __future__ import annotations

import argparse
import time

from hanppie.media_codec import install_media_codec_compat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--sn", required=True)
    parser.add_argument("--seconds", type=float, default=0.8)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    install_media_codec_compat()
    from robomaster import config, led, robot

    args = parse_args(argv)
    config.ROBOT_IP_STR = args.robot_ip
    s1 = robot.Robot()
    initialized = False
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", sn=args.sn)
        if not initialized:
            raise RuntimeError("DJI Robot.initialize() returned false")
        mode = s1.get_robot_mode()
        print(f"robot_mode_before={mode}")
        print(f"set_same_robot_mode={s1.set_robot_mode(mode)}")
        enabled = s1.led.set_led(
            comp=led.COMP_TOP_ALL,
            r=0,
            g=64,
            b=0,
            effect=led.EFFECT_ON,
        )
        print(f"top_led_green={enabled}")
        time.sleep(max(0.0, args.seconds))
        disabled = s1.led.set_led(
            comp=led.COMP_TOP_ALL,
            r=0,
            g=0,
            b=0,
            effect=led.EFFECT_OFF,
        )
        print(f"top_led_off={disabled}")
        return 0 if enabled and disabled else 1
    finally:
        if initialized:
            s1.close()
            print("official_robot_closed=True")


if __name__ == "__main__":
    raise SystemExit(main())
