#!/usr/bin/env python3
"""Probe the DJI RoboMaster Python SDK proxy handshake without motion."""

from __future__ import annotations

import argparse

from hanppie.media_codec import install_media_codec_compat


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Test whether the robot-side DJI SDK service accepts a session."
    )
    parser.add_argument("--robot-ip", required=True)
    parser.add_argument("--sn", default=None)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    install_media_codec_compat()
    from robomaster import config, conn, protocol

    args = parse_args(argv)
    config.ROBOT_IP_STR = args.robot_ip
    sdk_connection = conn.SdkConnection()
    try:
        result, local_addr, remote_addr = sdk_connection.request_connection(
            protocol.host2byte(9, 6),
            conn_type=conn.CONNECTION_WIFI_STA,
            proto_type=conn.CONNECTION_PROTO_UDP,
            sn=args.sn,
        )
        print(f"official_sdk_handshake={result} local_addr={local_addr} remote_addr={remote_addr}")
        return 0 if result else 2
    finally:
        sdk_connection.close()


if __name__ == "__main__":
    raise SystemExit(main())
