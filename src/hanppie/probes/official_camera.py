#!/usr/bin/env python3
"""Decode one S1 camera frame through DJI's official camera API."""

from __future__ import annotations

import argparse
import hashlib

from hanppie.media_codec import install_media_codec_compat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--sn", required=True)
    parser.add_argument("--timeout", type=float, default=10.0)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    install_media_codec_compat()
    from robomaster import config, robot

    args = parse_args(argv)
    config.ROBOT_IP_STR = args.robot_ip
    s1 = robot.Robot()
    initialized = False
    video_started = False
    try:
        initialized = s1.initialize(conn_type="sta", proto_type="udp", sn=args.sn)
        if not initialized:
            raise RuntimeError("DJI Robot.initialize() returned false")
        video_started = s1.camera.start_video_stream(display=False, resolution="720p")
        if not video_started:
            print("official_camera_supported=False")
            print("reason=S1 rejected the official SDK video-stream request")
            return 2
        image = s1.camera.read_cv2_image(timeout=args.timeout, strategy="newest")
        if image is None:
            raise RuntimeError("official camera API returned no decodable frame")
        digest = hashlib.sha256(image.tobytes()).hexdigest()
        print(f"official_camera_frame_shape={image.shape}")
        print(f"official_camera_frame_dtype={image.dtype}")
        print(f"official_camera_frame_mean={float(image.mean()):.3f}")
        print(f"official_camera_frame_sha256={digest}")
        return 0
    finally:
        if video_started:
            print(f"official_camera_stopped={s1.camera.stop_video_stream()}")
        if initialized:
            s1.close()
            print("official_robot_closed=True")


if __name__ == "__main__":
    raise SystemExit(main())
