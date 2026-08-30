from __future__ import annotations

import pytest

from hanppie.diagnosis import failsafe
from hanppie.lab.direct import DirectOdometry


def odometry(x: float, y: float) -> DirectOdometry:
    return DirectOdometry(1.0, 1, 80, 0.0, (x, y, 0.0, 0.0, 0.0), "")


class FakeReceiver:
    def __init__(self) -> None:
        self.closed = False

    def poll(self, _timeout: float) -> bool:
        return True

    def recv(self):
        return {
            "status": "moving",
            "baseline": (0.0, 0.0),
            "moving": (0.01, 0.0),
            "moving_sequence": 3,
        }

    def close(self) -> None:
        self.closed = True


class FakeSender:
    def close(self) -> None:
        return None


class FakeProcess:
    def __init__(self, **_kwargs) -> None:
        self.active = False
        self.exitcode = None

    def start(self) -> None:
        self.active = True

    def is_alive(self) -> bool:
        return self.active

    def terminate(self) -> None:
        self.active = False
        self.exitcode = -15

    def join(self, *, timeout: float) -> None:
        assert timeout > 0

    def kill(self) -> None:
        self.active = False
        self.exitcode = -9


class FakeContext:
    def Pipe(self, *, duplex: bool):
        assert not duplex
        return FakeReceiver(), FakeSender()

    def Process(self, **kwargs):
        assert kwargs["name"] == "hanppie-loss-stop-probe"
        return FakeProcess(**kwargs)


class FakeRecoveryRobot:
    recovered_x = 0.015

    def __init__(self, **_kwargs) -> None:
        self.closed = False

    def initialize(self, *, timeout: float) -> bool:
        assert timeout > 0
        return True

    def enter_control_mode(self) -> None:
        return None

    def disarm(self) -> None:
        return None

    def wait_for_odometry(self, *, timeout: float) -> DirectOdometry:
        assert timeout > 0
        return odometry(self.recovered_x, 0.0)

    def close(self) -> None:
        self.closed = True


def test_verify_direct_loss_stop_bounds_recovered_position(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(failsafe.multiprocessing, "get_context", lambda _mode: FakeContext())
    monkeypatch.setattr(
        failsafe, "_collect_post_loss_odometry", lambda _duration: [odometry(0.011, 0)]
    )
    monkeypatch.setattr(failsafe, "DirectRobot", FakeRecoveryRobot)

    evidence = failsafe.verify_direct_loss_stop(
        robot_ip="192.0.2.10",
        appid="b6359877",
        timeout=1,
    )

    assert evidence["child_exitcode"] == -15
    assert evidence["pre_loss_observed_displacement_m"] == 0.01
    assert evidence["post_loss_displacement_m"] == 0.005
    assert evidence["loss_stop_within_bound_observed"] is True


def test_verify_direct_loss_stop_rejects_excess_displacement(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(failsafe.multiprocessing, "get_context", lambda _mode: FakeContext())
    monkeypatch.setattr(failsafe, "_collect_post_loss_odometry", lambda _duration: [])
    monkeypatch.setattr(failsafe, "DirectRobot", FakeRecoveryRobot)
    FakeRecoveryRobot.recovered_x = 0.2
    try:
        with pytest.raises(RuntimeError, match="超过安全上界"):
            failsafe.verify_direct_loss_stop(
                robot_ip="192.0.2.10",
                appid="b6359877",
                timeout=1,
            )
    finally:
        FakeRecoveryRobot.recovered_x = 0.015
