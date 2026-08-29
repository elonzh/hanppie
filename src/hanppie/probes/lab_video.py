#!/usr/bin/env python3
"""Probe read-only S1 capabilities over the App-compatible Wi-Fi transport."""

from __future__ import annotations

import argparse

from robomaster_lab_sdk.robot import Robot


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Read the S1 battery and decode one camera frame without motion."
    )
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--appid", required=True)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--frame-timeout", type=float, default=10.0)
    parser.add_argument("--debug", action="store_true")
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    s1 = Robot(robot_ip=args.robot_ip, appid=args.appid, debug=args.debug)
    initialized = False
    video_started = False
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", timeout=args.timeout)
        if not initialized:
            raise RuntimeError("S1 App-compatible connection was not initialized")
        print(f"connected: {s1.info}")
        print(f"battery_percent={s1.base.get_battery()}")

        video_started = s1.camera.start_video_stream(display=False, resolution="720p")
        if not video_started:
            raise RuntimeError("S1 rejected the video-stream request")
        frame = s1.camera.read_video_frame(timeout=args.frame_timeout, strategy="newest")
        if frame is None:
            raise RuntimeError("S1 returned no decodable camera frame")
        print(
            f"camera_frame={frame.width}x{frame.height} format={frame.format.name} pts={frame.pts}"
        )
        return 0
    finally:
        if video_started:
            try:
                s1.camera.stop_video_stream()
                print("stopped video stream")
            except Exception as exc:
                print(f"warning: could not stop video stream: {exc}")
        if initialized:
            s1.close()
            print("closed App-compatible connection")


if __name__ == "__main__":
    raise SystemExit(main())
