"""Factory-style capability checks executed through one device session."""

from __future__ import annotations

import math
import time
from typing import Any

from hanppie.diagnosis.firmware import STOCK_HASHES, classify_runtime
from hanppie.diagnosis.session import DeviceSession

LED_COLORS = (
    ("red", 255, 0, 0),
    ("green", 0, 255, 0),
    ("blue", 0, 0, 255),
    ("white", 255, 255, 255),
)
CHASSIS_SEQUENCE = (
    ("forward", 0.15, 0.0, 0.0),
    ("backward", -0.15, 0.0, 0.0),
    ("left", 0.0, 0.15, 0.0),
    ("right", 0.0, -0.15, 0.0),
    ("counterclockwise", 0.0, 0.0, 15.0),
    ("clockwise", 0.0, 0.0, -15.0),
)
GIMBAL_SEQUENCE = (
    ("pitch-positive", 15.0, 0.0),
    ("pitch-negative", -15.0, 0.0),
    ("yaw-positive", 0.0, 15.0),
    ("yaw-negative", 0.0, -15.0),
)
SPEAKER_TONE_ID = 0x107


class DiagnosisChecks:
    def __init__(self, session: DeviceSession) -> None:
        self.session = session

    def handlers(self):
        """Return the explicit diagnosis dispatch table."""

        return {
            "discovery": self.discovery,
            "app": self.app,
            "video": self.video,
            "lab": self.lab,
            "led": self.led,
            "speaker": self.speaker,
            "chassis": self.chassis,
            "gimbal": self.gimbal,
            "infrared": self.infrared,
            "gel": self.gel,
            "adb": self.adb,
            "system": self.system,
        }

    def discovery(self) -> dict[str, Any]:
        robots = self.session.discover_devices()
        if not robots:
            raise TimeoutError("未收到 S1 App 广播")
        selected = self.session.adopt_discovery(robots)
        return {
            "_summary": "收到并解析 S1 App 广播",
            "broadcast_count": len(robots),
            "selected": {
                "robot_ip": self.session.robot_ip,
                "appid": self.session.appid,
                "mac": selected.robot_mac,
                "pairing": selected.pairing,
            },
        }

    def app(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        deadline = self._deadline(min(3.0, self.session.config.timeout))
        battery = robot.base.get_battery()
        while battery is None and self._before(deadline):
            self.session.sleep(0.05)
            battery = robot.base.get_battery()
        battery_valid = isinstance(battery, int) and 1 <= battery <= 100
        return {
            "_summary": (
                "App 会话已建立，电量有效" if battery_valid else "App 会话已建立，但电量值不可信"
            ),
            "connection_state": robot.info.state,
            "battery_percent": battery,
            "battery_valid": battery_valid,
            "transport": "App-compatible UDP",
        }

    def video(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        started = False
        try:
            started = robot.camera.start_video_stream(display=False, resolution="720p")
            if not started:
                raise RuntimeError("机器人拒绝视频流请求")
            frame = robot.camera.read_video_frame(
                timeout=self.session.config.timeout, strategy="newest"
            )
            if frame is None:
                raise TimeoutError("未收到可解码视频帧")
            return {
                "_summary": "已解码一帧 720p H.264 视频",
                "width": frame.width,
                "height": frame.height,
                "format": frame.format.name,
                "pts": frame.pts,
            }
        finally:
            if started:
                robot.camera.stop_video_stream()

    def lab(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        return {
            "_summary": "Lab 程序已启动并返回当前会话遥测",
            "bridge_workers": robot.bridge.worker_threads,
            "telemetry": self.session.telemetry(),
        }

    def led(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        completed: list[dict[str, Any]] = []
        try:
            for name, red, green, blue in LED_COLORS:
                if not robot.set_led(component="all", red=red, green=green, blue=blue, effect="on"):
                    raise RuntimeError(f"装甲灯 {name} 命令未发送")
                sequence = robot.bridge.command_sequence
                telemetry = self.session.wait_command("led.set", sequence)
                if telemetry.get("last_command_ok") is not True:
                    raise RuntimeError(
                        f"机内装甲灯 {name} 调用失败：{telemetry.get('last_command_error')}"
                    )
                completed.append({"name": name, "rgb": [red, green, blue]})
                self.session.sleep(0.35)

            if not robot.set_led(component="all", red=0, green=0, blue=0, effect="off"):
                raise RuntimeError("装甲灯关闭命令未发送")
            sequence = robot.bridge.command_sequence
            telemetry = self.session.wait_command("led.set", sequence)
            if telemetry.get("last_command_ok") is not True:
                raise RuntimeError("机内装甲灯关闭调用失败")
            return {
                "_summary": "红、绿、蓝、白循环及关闭均获 controller 确认；未做外部视觉确认",
                "colors": completed,
                "off_acknowledged": True,
            }
        finally:
            robot.set_led(component="all", red=0, green=0, blue=0, effect="off")

    def speaker(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        if not robot.call("media", "play_sound", sound=SPEAKER_TONE_ID):
            raise RuntimeError("扬声器命令未发送")
        sequence = robot.bridge.command_sequence
        telemetry = self.session.wait_command("media.play_sound", sequence)
        if telemetry.get("last_command_ok") is not True:
            raise RuntimeError(f"机内扬声器调用失败：{telemetry.get('last_command_error')}")
        self.session.sleep(0.5)
        return {
            "_summary": "内置提示音调用获 controller 确认；未做外部听觉确认",
            "sound_id": SPEAKER_TONE_ID,
            "acknowledged": True,
        }

    def chassis(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        initial = self.session.telemetry()
        steps: list[dict[str, Any]] = []
        try:
            for name, x, y, z in CHASSIS_SEQUENCE:
                before = self.session.telemetry()
                if not robot.bridge.send(x=x, y=y, z=z):
                    raise RuntimeError(f"底盘 {name} 命令未发送")
                sequence = robot.bridge.command_sequence
                active = self.session.wait_command("chassis.move_with_speed", sequence)
                if active.get("last_command_ok") is not True:
                    raise RuntimeError(
                        f"机内底盘 {name} 调用失败：{active.get('last_command_error')}"
                    )
                self.session.sleep(robot.config.command_timeout + 0.25)
                stopped = self.session.wait_telemetry(
                    lambda values, expected=sequence: (
                        int(values.get("rx_command_seq", 0) or 0) >= expected
                        and values.get("motion_active") is False
                    )
                )
                steps.append(
                    {
                        "direction": name,
                        "command": {"x_mps": x, "y_mps": y, "z_dps": z},
                        "watchdog_stopped": True,
                        "delta": self._pose_delta(before, stopped),
                    }
                )
                self._stop_and_confirm(robot)
                self.session.sleep(0.1)
        finally:
            robot.bridge.stop_robot()
        final = self.session.telemetry()
        return {
            "_summary": "底盘前后左右及双向旋转均获确认，每步均由 watchdog 归零",
            "steps": steps,
            "net_delta": self._pose_delta(initial, final),
        }

    def gimbal(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        initial = self.session.telemetry()
        steps: list[dict[str, Any]] = []
        try:
            for name, pitch, yaw in GIMBAL_SEQUENCE:
                before = self.session.telemetry()
                if not robot.bridge.send(gimbal_pitch=pitch, gimbal_yaw=yaw):
                    raise RuntimeError(f"云台 {name} 命令未发送")
                sequence = robot.bridge.command_sequence
                active = self.session.wait_command("gimbal.rotate_with_speed", sequence)
                if active.get("last_command_ok") is not True:
                    raise RuntimeError(
                        f"机内云台 {name} 调用失败：{active.get('last_command_error')}"
                    )
                self.session.sleep(robot.config.command_timeout + 0.25)
                stopped = self.session.wait_telemetry(
                    lambda values, expected=sequence: (
                        int(values.get("rx_command_seq", 0) or 0) >= expected
                        and values.get("motion_active") is False
                    )
                )
                steps.append(
                    {
                        "direction": name,
                        "command": {"pitch_dps": pitch, "yaw_dps": yaw},
                        "watchdog_stopped": True,
                        "delta": self._gimbal_delta(before, stopped),
                    }
                )
                self._stop_and_confirm(robot)
                self.session.sleep(0.1)

            if not robot.call("gimbal", "recenter"):
                raise RuntimeError("云台回中命令未发送")
            sequence = robot.bridge.command_sequence
            centered = self.session.wait_command("gimbal.recenter", sequence)
            if centered.get("last_command_ok") is not True:
                raise RuntimeError("机内云台回中调用失败")
            self.session.sleep(0.8)
            final = self.session.telemetry()
            return {
                "_summary": "云台俯仰和偏航双向运动均获确认，并已执行回中",
                "steps": steps,
                "recenter_acknowledged": True,
                "initial": self._gimbal_pose(initial),
                "final": self._gimbal_pose(final),
            }
        finally:
            robot.bridge.stop_robot()

    def infrared(self) -> dict[str, Any]:
        return self._fire("infrared", "blaster.fire_ir")

    def gel(self) -> dict[str, Any]:
        return self._fire("gel", "blaster.fire_gel")

    def adb(self) -> dict[str, Any]:
        identity = self.session.ensure_adb()
        if "uid=0(root)" not in identity:
            raise RuntimeError(f"ADB shell 不是 root：{identity}")
        version = self.session.run_command(
            [self.session.config.adb, "version"], timeout=5.0
        ).stdout.splitlines()
        return {
            "_summary": "临时 ADB 已进入 root device 状态",
            "identity": identity,
            "host_adb": version[:2],
        }

    def system(self) -> dict[str, Any]:
        self.session.ensure_adb()
        properties = {
            name: self.session.adb_shell("getprop", name)
            for name in (
                "ro.product.manufacturer",
                "ro.product.model",
                "ro.product.name",
                "ro.build.version.release",
                "ro.build.version.sdk",
                "ro.build.type",
                "ro.build.fingerprint",
            )
        }
        services = {
            name: self.session.adb_shell("getprop", f"init.svc.{name}")
            for name in (
                "start_dji_system",
                "dji_monitor",
                "dji_sys",
                "dji_hdvt_uav",
                "dji_camera",
                "dji_vision",
                "dji_network",
                "dji_sw_uav",
                "dji_scratch",
                "dji_blackbox",
                "dji_perception",
            )
        }
        python_version = self.session.adb_shell(
            "/data/python_files/bin/python", "-S", "-c", "import sys; print(sys.version)"
        )
        if "3.6.6" not in python_version:
            raise RuntimeError(f"机内 Python 版本探针返回异常：{python_version}")
        processes = self.session.adb_shell("ps", check=False)
        sockets = self.session.adb_shell("netstat", "-an", check=False)
        unix_sockets = self.session.adb_shell("cat", "/proc/net/unix", check=False)
        mounts = self.session.adb_shell("cat", "/proc/mounts")
        hashes = {
            path: self.session.adb_shell("/system/xbin/busybox", "sha256sum", path).split()[0]
            for path in STOCK_HASHES
        }
        key_files = {
            path: self.session.adb_shell("ls", "-l", path, check=False)
            for path in (
                "/init.rc",
                "/system/bin/start_dji_system.sh",
                "/system/etc/dji.json",
                "/system/bin/dji_hdvt_uav",
                "/system/bin/dji_sys",
                "/system/bin/dji_vision",
                "/system/bin/dji_camera",
                "/data/dji_scratch/bin/dji_scratch.py",
                "/data/dji_scratch/src/robomaster",
                "/data/python_files/bin/python",
                "/system/bin/adb_en.sh",
                "/sbin/adbd",
            )
        }
        return {
            "_summary": "机内系统、服务、端口和关键文件快照已采集",
            "identity": self.session.adb_shell("id"),
            "properties": properties,
            "python": python_version,
            "services": services,
            "processes": processes.splitlines(),
            "network_sockets": sockets.splitlines(),
            "unix_sockets": unix_sockets.splitlines(),
            "relevant_mounts": [
                line
                for line in mounts.splitlines()
                if any(marker in line for marker in (" /system ", "dji.json", "dji_hdvt_uav"))
            ],
            "key_files": key_files,
            "critical_file_sha256": hashes,
            "runtime_classification": classify_runtime(hashes),
            "udp_30030_listening": any(":30030" in line for line in sockets.splitlines()),
        }

    def _fire(self, fire_type: str, command: str) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        if not robot.fire(fire_type):
            raise RuntimeError("发射命令未发送")
        sequence = robot.bridge.command_sequence
        telemetry = self.session.wait_command(command, sequence)
        if telemetry.get("last_command_ok") is not True:
            raise RuntimeError(f"机内发射调用失败：{telemetry.get('last_command_error')}")
        effect = "未验证外部红外接收" if fire_type == "infrared" else "空仓，未验证弹丸物理发射"
        return {
            "_summary": f"controller 调用已确认；{effect}",
            "fire_type": fire_type,
            "count": 1,
            "acknowledged": True,
        }

    def _stop_and_confirm(self, robot) -> None:
        if not robot.bridge.stop_robot():
            raise RuntimeError("停止命令未发送")
        sequence = robot.bridge.command_sequence
        stopped = self.session.wait_command("system.stop", sequence)
        if stopped.get("motion_active") is not False:
            raise RuntimeError("停止命令未使运动状态归零")

    @staticmethod
    def _pose_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, float | None]:
        return {
            "x_m": DiagnosisChecks._difference(before, after, "x"),
            "y_m": DiagnosisChecks._difference(before, after, "y"),
            "yaw_deg": DiagnosisChecks._difference(before, after, "yaw"),
        }

    @staticmethod
    def _gimbal_delta(before: dict[str, Any], after: dict[str, Any]) -> dict[str, float | None]:
        return {
            "pitch_deg": DiagnosisChecks._difference(before, after, "gimbal_pitch"),
            "yaw_deg": DiagnosisChecks._difference(before, after, "gimbal_yaw"),
        }

    @staticmethod
    def _gimbal_pose(values: dict[str, Any]) -> dict[str, Any]:
        return {
            "pitch_deg": values.get("gimbal_pitch"),
            "yaw_deg": values.get("gimbal_yaw"),
        }

    @staticmethod
    def _difference(before: dict[str, Any], after: dict[str, Any], field: str) -> float | None:
        try:
            start = float(before[field])
            end = float(after[field])
        except (KeyError, TypeError, ValueError):
            return None
        if not math.isfinite(start) or not math.isfinite(end):
            return None
        return round(end - start, 4)

    @staticmethod
    def _deadline(duration: float) -> float:
        return time.monotonic() + duration

    @staticmethod
    def _before(deadline: float) -> bool:
        return time.monotonic() < deadline
