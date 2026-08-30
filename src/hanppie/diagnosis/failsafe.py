"""Abnormal host-exit probe for the S1 native control session."""

from __future__ import annotations

import math
import multiprocessing
import select
import socket
import time
from multiprocessing.connection import Connection
from typing import Any

from hanppie.lab import protocol
from hanppie.lab.app import open_udp
from hanppie.lab.direct import DirectOdometry, DirectRobot, decode_direct_odometry

MOTION_COMMAND_SECONDS = 0.45
POST_LOSS_OBSERVATION_SECONDS = 1.5
MAX_POST_LOSS_DISPLACEMENT_METERS = 0.05
MIN_COMMANDED_PHASE_DISPLACEMENT_METERS = 0.003


def _position(sample: DirectOdometry) -> tuple[float, float] | None:
    if sample.x is None or sample.y is None:
        return None
    if not math.isfinite(sample.x) or not math.isfinite(sample.y):
        return None
    return sample.x, sample.y


def _distance(start: tuple[float, float], end: tuple[float, float]) -> float:
    return math.hypot(end[0] - start[0], end[1] - start[1])


def _motion_worker(
    sender: Connection,
    robot_ip: str,
    appid: str,
    timeout: float,
) -> None:
    robot = DirectRobot(robot_ip=robot_ip, appid=appid)
    handed_off = False
    try:
        robot.initialize(timeout=timeout)
        robot.enter_control_mode()
        robot.arm()
        baseline = robot.wait_for_odometry(timeout=timeout)
        if baseline is None or _position(baseline) is None:
            raise TimeoutError("动作前未收到有效位置遥测")
        robot.chassis.drive_speed(y=0.15, lease_seconds=5.0)
        time.sleep(MOTION_COMMAND_SECONDS)
        moving = robot.wait_for_odometry(
            after=time.monotonic() - 0.15,
            timeout=timeout,
        )
        if moving is None or _position(moving) is None:
            raise TimeoutError("动作中未收到有效位置遥测")
        sender.send(
            {
                "status": "moving",
                "baseline": _position(baseline),
                "moving": _position(moving),
                "moving_sequence": moving.sequence,
            }
        )
        handed_off = True
        # The parent terminates this process here. Keeping it alive is
        # intentional: no Python finally block may send a neutral command.
        time.sleep(30.0)
    except Exception as exc:
        sender.send({"status": "error", "error": f"{type(exc).__name__}: {exc}"})
    finally:
        if not handed_off:
            robot.close()
        sender.close()


def _collect_post_loss_odometry(duration: float) -> list[DirectOdometry]:
    connection = open_udp("0.0.0.0", protocol.LOCAL_CONTROL_PORT)
    connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
    samples: list[DirectOdometry] = []
    deadline = time.monotonic() + duration
    try:
        while time.monotonic() < deadline:
            readable, _, _ = select.select(
                [connection],
                [],
                [],
                min(0.05, max(0.0, deadline - time.monotonic())),
            )
            for ready in readable:
                try:
                    data, _ = ready.recvfrom(65535)
                except BlockingIOError:
                    continue
                for frame in protocol.parse_duss_frames(data):
                    if not frame.valid:
                        continue
                    telemetry = decode_direct_odometry(frame)
                    if telemetry is not None and _position(telemetry) is not None:
                        samples.append(telemetry)
    finally:
        connection.close()
    return samples


def verify_direct_loss_stop(
    *,
    robot_ip: str,
    appid: str,
    timeout: float,
) -> dict[str, Any]:
    """Terminate an armed host and bound subsequent movement from passive telemetry."""

    context = multiprocessing.get_context("spawn")
    receiver, sender = context.Pipe(duplex=False)
    process = context.Process(
        target=_motion_worker,
        args=(sender, robot_ip, appid, timeout),
        name="hanppie-loss-stop-probe",
    )
    process.start()
    sender.close()
    worker: dict[str, Any] | None = None
    try:
        if not receiver.poll(timeout + 5.0):
            raise TimeoutError("失联测试子进程未进入运动状态")
        worker = receiver.recv()
        if worker.get("status") != "moving":
            raise RuntimeError(str(worker.get("error", "失联测试子进程失败")))
    finally:
        if process.is_alive():
            process.terminate()
        process.join(timeout=2.0)
        if process.is_alive():
            process.kill()
            process.join(timeout=1.0)
        receiver.close()

    if worker is None:
        raise RuntimeError("失联测试没有子进程证据")
    samples = _collect_post_loss_odometry(POST_LOSS_OBSERVATION_SECONDS)
    positions = [position for sample in samples if (position := _position(sample)) is not None]

    recovery = DirectRobot(robot_ip=robot_ip, appid=appid)
    try:
        recovery.initialize(timeout=timeout)
        recovery.enter_control_mode()
        recovery.disarm()
        recovered = recovery.wait_for_odometry(timeout=timeout)
        if recovered is None or _position(recovered) is None:
            raise TimeoutError("恢复会话后未收到有效位置遥测")
        recovered_position = _position(recovered)
        assert recovered_position is not None
    finally:
        recovery.close()

    baseline = tuple(worker["baseline"])
    moving = tuple(worker["moving"])
    commanded_displacement = _distance(baseline, moving)
    if commanded_displacement < MIN_COMMANDED_PHASE_DISPLACEMENT_METERS:
        raise RuntimeError(
            f"强制退出前没有观察到足够位移，无法证明失联停止：{commanded_displacement:.4f} m"
        )
    post_loss_displacement = _distance(moving, recovered_position)
    stopped = post_loss_displacement <= MAX_POST_LOSS_DISPLACEMENT_METERS
    if not stopped:
        raise RuntimeError(
            "主机进程退出后的位移超过安全上界："
            f"{post_loss_displacement:.4f} m > {MAX_POST_LOSS_DISPLACEMENT_METERS:.4f} m"
        )
    return {
        "child_exitcode": process.exitcode,
        "motion_command": {"y_mps": 0.15, "lease_seconds": 5.0},
        "pre_loss_observed_displacement_m": round(commanded_displacement, 4),
        "post_loss_observation_seconds": POST_LOSS_OBSERVATION_SECONDS,
        "passive_post_loss_samples": len(positions),
        "post_loss_displacement_m": round(post_loss_displacement, 4),
        "maximum_allowed_post_loss_displacement_m": MAX_POST_LOSS_DISPLACEMENT_METERS,
        "loss_stop_within_bound_observed": True,
        "measurement": "0x48/0x08 inferred power-on planar position before and after recovery",
    }
