"""Passive S1 discovery for the diagnosis workflow."""

from __future__ import annotations

import select
import socket
import time
from collections.abc import Callable
from typing import Any

from hanppie.lab.app import open_udp
from hanppie.lab.protocol import APP_PORT, RobotBroadcast, parse_robot_broadcast


def discover_robots(
    timeout: float,
    *,
    socket_factory: Callable[..., socket.socket] = open_udp,
    select_fn: Callable[..., tuple[list[Any], list[Any], list[Any]]] = select.select,
) -> list[RobotBroadcast]:
    """Collect unique S1 App broadcasts during a bounded interval."""

    connection = socket_factory("0.0.0.0", APP_PORT, broadcast=True)
    deadline = time.monotonic() + max(0.0, timeout)
    robots: dict[tuple[str, str], RobotBroadcast] = {}
    try:
        while time.monotonic() < deadline:
            remaining = min(0.2, max(0.0, deadline - time.monotonic()))
            readable, _, _ = select_fn([connection], [], [], remaining)
            for ready in readable:
                payload, address = ready.recvfrom(65535)
                broadcast = parse_robot_broadcast(payload)
                if broadcast is None:
                    continue
                robot_ip = broadcast.robot_ip or address[0]
                normalized = RobotBroadcast(
                    robot_ip=robot_ip,
                    robot_mac=broadcast.robot_mac,
                    appid=broadcast.appid,
                    pairing=broadcast.pairing,
                )
                robots[(normalized.robot_ip, normalized.appid)] = normalized
    finally:
        connection.close()
    return list(robots.values())
