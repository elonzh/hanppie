"""Factory-style capability checks executed through one device session."""

from __future__ import annotations

import array
import math
import sys
import time
from typing import Any

from hanppie.diagnosis.firmware import STOCK_HASHES, classify_runtime
from hanppie.diagnosis.session import DeviceSession
from hanppie.lab.audio import MICROPHONE_SAMPLE_RATE, SPEAKER_SAMPLE_RATE

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
SPEAKER_SOUNDS = (
    ("solmization-1C", 0x107),
    ("shoot", 0x102),
)


class DiagnosisChecks:
    def __init__(self, session: DeviceSession) -> None:
        self.session = session

    def handlers(self):
        """Return the explicit diagnosis dispatch table."""

        return {
            "discovery": self.discovery,
            "app": self.app,
            "direct": self.direct,
            "video": self.video,
            "microphone": self.microphone,
            "lab": self.lab,
            "led": self.led,
            "speaker": self.speaker,
            "muzzle": self.muzzle,
            "chassis": self.chassis,
            "failsafe": self.failsafe,
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

    def direct(self) -> dict[str, Any]:
        robot = self.session.ensure_direct(control_mode=True)
        odometry = robot.wait_for_odometry(timeout=self.session.config.timeout)
        gimbal = robot.wait_for_gimbal(timeout=self.session.config.timeout)
        if odometry is None:
            raise TimeoutError("进入原生控制模式后未收到 0x48/0x08 底盘遥测")
        if gimbal is None:
            raise TimeoutError("进入原生控制模式后未收到 0x48/0x08 云台遥测")
        return {
            "_summary": "未上传 Lab 程序，已进入原生控制模式并持续收到 DUSS 遥测",
            "backend": "AppEnvelope direct DUSS",
            "lab_program_uploaded": False,
            "armed": robot.armed,
            "battery_percent": odometry.battery_percent,
            "odometry_sequence": odometry.sequence,
            "gimbal_sequence": gimbal.sequence,
            "gimbal_raw": list(gimbal.values),
            "gimbal_angles_degrees": {
                "ground_yaw": gimbal.ground_yaw_degrees,
                "ground_pitch": gimbal.ground_pitch_degrees,
                "yaw": gimbal.yaw_degrees,
                "pitch": gimbal.pitch_degrees,
            },
            "gimbal_status_raw": gimbal.flag,
        }

    def microphone(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        started = False
        try:
            started = robot.camera.start_audio_stream()
            if not started:
                raise RuntimeError("机器人麦克风流请求未发送")
            samples = self._collect_audio_stats(robot.camera, frames=3)
            if not samples:
                raise TimeoutError("未收到可解码的 S1 麦克风音频")
            return {
                "_summary": "已接收并解码 S1 麦克风的 48 kHz 单声道 Opus 音频",
                "format": "48 kHz mono signed 16-bit PCM",
                "frames": samples,
                "maximum_peak": max(sample["peak"] for sample in samples),
                "maximum_rms": max(sample["rms"] for sample in samples),
            }
        finally:
            if started:
                robot.camera.stop_audio_stream()

    def lab(self) -> dict[str, Any]:
        robot = self.session.ensure_lab()
        return {
            "_summary": "Lab 程序已启动并返回当前会话遥测",
            "bridge_workers": robot.bridge.worker_threads,
            "telemetry": self.session.telemetry(),
        }

    def led(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        completed: list[dict[str, Any]] = []
        try:
            for name, red, green, blue in LED_COLORS:
                ack = robot.set_led(component="all", red=red, green=green, blue=blue, effect="on")
                completed.append(
                    {"name": name, "rgb": [red, green, blue], "ack_sequence": ack.sequence}
                )
                self.session.sleep(0.35)

            off = robot.set_led(component="all", red=0, green=0, blue=0, effect="off")
            return {
                "_summary": "红、绿、蓝、白循环及关闭均获原生 DUSS ACK；未做外部视觉确认",
                "colors": completed,
                "off_ack_sequence": off.sequence,
                "backend": "AppEnvelope direct DUSS 0x3f/0x33",
            }
        finally:
            robot.set_led(component="all", red=0, green=0, blue=0, effect="off")

    def speaker(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        host_pcm = self._test_host_pcm_speaker(robot)
        robot = self.session.ensure_app()
        audio_started = robot.camera.start_audio_stream()
        baseline = self._collect_audio_stats(robot.camera, frames=3) if audio_started else []
        sounds: list[dict[str, Any]] = []
        try:
            for name, sound_id in SPEAKER_SOUNDS:
                ack = robot.play_sound(sound_id)
                samples = self._collect_audio_stats(robot.camera, frames=5) if audio_started else []
                sounds.append(
                    {
                        "name": name,
                        "sound_id": sound_id,
                        "duss_ack_sequence": ack.sequence,
                        "microphone_loopback": samples,
                    }
                )
        finally:
            if audio_started:
                robot.camera.stop_audio_stream()

        baseline_rms = max((sample["rms"] for sample in baseline), default=0.0)
        sound_rms = max(
            (sample["rms"] for sound in sounds for sample in sound["microphone_loopback"]),
            default=0.0,
        )
        ratio = round(sound_rms / baseline_rms, 2) if baseline_rms else None
        acoustic_loopback = sound_rms > max(200.0, baseline_rms * 1.8)
        summary = "音阶和射击音效均获原生 DUSS ACK"
        if acoustic_loopback:
            summary += "，机身麦克风同时记录到显著声压变化"
        else:
            summary += "；未取得可判定的物理声学回环证据"
        if host_pcm.get("acoustic_loopback_observed"):
            summary += "；Host PCM 测试音播放也取得声学回环"
        else:
            summary += "；Host PCM 已发送但未取得可判定的声学回环"
        return {
            "_summary": summary,
            "sounds": sounds,
            "baseline_microphone": baseline,
            "maximum_sound_rms": sound_rms,
            "rms_ratio": ratio,
            "acoustic_loopback_observed": acoustic_loopback,
            "host_pcm": host_pcm,
            "backend": "AppEnvelope direct media",
        }

    def _test_host_pcm_speaker(self, robot) -> dict[str, Any]:
        audio_started = robot.camera.start_audio_stream()
        try:
            baseline = (
                self._collect_audio_stats(robot.camera, frames=3, tone_frequency=440.0)
                if audio_started
                else []
            )
        finally:
            if audio_started:
                robot.camera.stop_audio_stream()

        self.session.close_robot()
        robot = self.session.ensure_app()
        output_audio_started = False
        try:
            tone = self._tone_pcm(
                frequency=440.0,
                duration=1.0,
                amplitude=8000,
                sample_rate=SPEAKER_SAMPLE_RATE,
            )
            packets = robot.audio.play_pcm(tone)
            output_audio_started = robot.camera.start_audio_stream()
            loopback = (
                self._collect_audio_stats(robot.camera, frames=50, tone_frequency=440.0)
                if output_audio_started
                else []
            )
        finally:
            if output_audio_started:
                robot.camera.stop_audio_stream()
        baseline_rms = max((sample["rms"] for sample in baseline), default=0.0)
        sound_rms = max((sample["rms"] for sample in loopback), default=0.0)
        baseline_tone = max((sample["tone_amplitude"] for sample in baseline), default=0.0)
        sound_tone = max((sample["tone_amplitude"] for sample in loopback), default=0.0)
        return {
            "format": "12 kHz mono signed 16-bit PCM to length-prefixed Opus",
            "frequency_hz": 440.0,
            "duration_seconds": 1.0,
            "amplitude": 8000,
            "transfer_packets": packets,
            "fresh_app_session_before_upload": True,
            "baseline_microphone": baseline,
            "microphone_loopback": loopback,
            "rms_ratio": round(sound_rms / baseline_rms, 2) if baseline_rms else None,
            "tone_amplitude_ratio": (
                round(sound_tone / baseline_tone, 2) if baseline_tone else None
            ),
            "acoustic_loopback_observed": sound_tone > max(100.0, baseline_tone * 2.5),
        }

    def muzzle(self) -> dict[str, Any]:
        robot = self.session.ensure_app()
        completed: list[str] = []
        try:
            for fire, enabled, name in (
                (False, True, "steady:on"),
                (False, False, "steady:off"),
                (True, True, "fire:on"),
                (True, False, "fire:off"),
            ):
                robot.set_muzzle_led(fire=fire, enabled=enabled)
                completed.append(name)
                self.session.sleep(0.35)
            return {
                "_summary": "枪口常亮和开火灯效均获原生 DUSS ACK；未做外部视觉确认",
                "sequence": completed,
                "backend": "AppEnvelope direct DUSS 0x3f/0x33",
            }
        finally:
            robot.set_muzzle_led(fire=False, enabled=False)
            robot.set_muzzle_led(fire=True, enabled=False)

    def chassis(self) -> dict[str, Any]:
        robot = self.session.ensure_direct(control_mode=True)
        robot.arm()
        initial = robot.wait_for_odometry(timeout=self.session.config.timeout)
        if initial is None:
            raise TimeoutError("底盘动作前未收到原生里程计遥测")
        steps: list[dict[str, Any]] = []
        try:
            for name, x, y, z in CHASSIS_SEQUENCE:
                before = robot.wait_for_odometry(timeout=self.session.config.timeout)
                if before is None:
                    raise TimeoutError(f"底盘 {name} 动作前未收到里程计")
                sent_at = time.monotonic()
                robot.chassis.drive_speed(x=x, y=y, z=z, lease_seconds=0.25)
                moving = robot.wait_for_odometry(
                    after=sent_at + 0.12,
                    timeout=self.session.config.timeout,
                )
                if moving is None:
                    raise TimeoutError(f"底盘 {name} 动作中未收到里程计")
                self.session.sleep(max(0.0, sent_at + 0.4 - time.monotonic()))
                stopped = robot.wait_for_odometry(
                    after=sent_at + 0.3,
                    timeout=self.session.config.timeout,
                )
                if stopped is None:
                    raise TimeoutError(f"底盘 {name} 动作后未收到里程计")
                steps.append(
                    {
                        "direction": name,
                        "command": {"x_mps": x, "y_mps": y, "z_dps": z},
                        "host_lease_seconds": 0.25,
                        "neutral_sent_after_lease": True,
                        "delta": self._direct_pose_delta(before, stopped),
                        "moving_raw_values_2_4": moving.raw_motion_values,
                        "stopped_raw_values_2_4": stopped.raw_motion_values,
                    }
                )
                robot.chassis.stop()
                self.session.sleep(0.1)
        finally:
            robot.disarm()
        final = robot.wait_for_odometry(timeout=self.session.config.timeout)
        if final is None:
            final = initial
        return {
            "_summary": "底盘六方向已通过原生控制通道执行，每步在 250 ms 租约后归零",
            "steps": steps,
            "net_delta": self._direct_pose_delta(initial, final),
            "backend": "AppEnvelope control channel",
        }

    def gimbal(self) -> dict[str, Any]:
        robot = self.session.ensure_direct(control_mode=True)
        robot.arm()
        initial = robot.wait_for_gimbal(timeout=self.session.config.timeout)
        if initial is None:
            raise TimeoutError("云台动作前未收到原生遥测")
        steps: list[dict[str, Any]] = []
        try:
            for name, pitch, yaw in GIMBAL_SEQUENCE:
                before = robot.wait_for_gimbal(timeout=self.session.config.timeout)
                if before is None:
                    raise TimeoutError(f"云台 {name} 动作前未收到遥测")
                sent_at = time.monotonic()
                robot.gimbal.drive_speed(
                    pitch_speed=pitch,
                    yaw_speed=yaw,
                    lease_seconds=0.25,
                )
                self.session.sleep(0.4)
                stopped = robot.wait_for_gimbal(
                    after=sent_at + 0.3,
                    timeout=self.session.config.timeout,
                )
                if stopped is None:
                    raise TimeoutError(f"云台 {name} 动作后未收到遥测")
                steps.append(
                    {
                        "direction": name,
                        "command": {"pitch_dps": pitch, "yaw_dps": yaw},
                        "host_lease_seconds": 0.25,
                        "neutral_sent_after_lease": True,
                        "raw_delta": [
                            end - start
                            for start, end in zip(before.values, stopped.values, strict=True)
                        ],
                    }
                )
                robot.gimbal.stop()
                self.session.sleep(0.1)
            final = robot.wait_for_gimbal(timeout=self.session.config.timeout) or initial
            return {
                "_summary": "云台俯仰和偏航双向运动已通过原生 DUSS 速度控制执行并归零",
                "steps": steps,
                "initial_raw": list(initial.values),
                "final_raw": list(final.values),
                "backend": "AppEnvelope direct DUSS 0x04/0x69",
            }
        finally:
            robot.disarm()

    def failsafe(self) -> dict[str, Any]:
        evidence = self.session.verify_direct_loss_stop()
        evidence["_summary"] = "直控主机进程被强制终止后，观察窗口内的累计位移未超过安全上界"
        return evidence

    def infrared(self) -> dict[str, Any]:
        robot = self.session.ensure_direct(control_mode=True)
        robot.arm()
        try:
            led = robot.set_muzzle_led(fire=True, enabled=True)
            sound = robot.play_sound(0x102)
            robot.fire_infrared(lease_seconds=0.12)
            self.session.sleep(0.2)
        finally:
            robot.disarm()
            robot.set_muzzle_led(fire=True, enabled=False)
        return {
            "_summary": "红外触发走原生控制通道，射击音效和枪口闪光获 DUSS ACK；未验证外部接收",
            "fire_type": "infrared",
            "count": 1,
            "control_lease_seconds": 0.12,
            "muzzle_led_ack_sequence": led.sequence,
            "shoot_sound_ack_sequence": sound.sequence,
            "backend": "AppEnvelope direct control and DUSS",
        }

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
            "_summary": f"发射、射击音效和枪口闪光均获 controller 确认；{effect}",
            "fire_type": fire_type,
            "count": 1,
            "acknowledged": True,
            "effects": {
                "muzzle_led": telemetry.get("last_fire_led_ok"),
                "shoot_sound": telemetry.get("last_fire_sound_ok"),
                "actuator": telemetry.get("last_fire_actuator_ok"),
            },
        }

    def _collect_audio_stats(
        self, camera, *, frames: int, tone_frequency: float | None = None
    ) -> list[dict[str, Any]]:
        samples: list[dict[str, Any]] = []
        deadline = self._deadline(self.session.config.timeout)
        while len(samples) < frames and self._before(deadline):
            pcm = camera.read_audio_frame(timeout=min(0.5, max(0.0, deadline - time.monotonic())))
            if not pcm:
                continue
            level = self._pcm_level(pcm, tone_frequency=tone_frequency)
            level["bytes"] = len(pcm)
            samples.append(level)
        return samples

    @staticmethod
    def _pcm_level(pcm: bytes, *, tone_frequency: float | None = None) -> dict[str, int | float]:
        even = pcm[: len(pcm) - (len(pcm) % 2)]
        values = array.array("h")
        values.frombytes(even)
        if sys.byteorder == "big":
            values.byteswap()
        if not values:
            return {"samples": 0, "peak": 0, "rms": 0.0}
        peak = max(abs(value) for value in values)
        rms = math.sqrt(sum(value * value for value in values) / len(values))
        result: dict[str, int | float] = {
            "samples": len(values),
            "peak": peak,
            "rms": round(rms, 2),
        }
        if tone_frequency is not None:
            mean = sum(values) / len(values)
            angular_step = 2 * math.pi * tone_frequency / MICROPHONE_SAMPLE_RATE
            in_phase = sum(
                (value - mean) * math.cos(angular_step * index)
                for index, value in enumerate(values)
            )
            quadrature = sum(
                (value - mean) * math.sin(angular_step * index)
                for index, value in enumerate(values)
            )
            result["tone_frequency_hz"] = tone_frequency
            result["tone_amplitude"] = round(2 * math.hypot(in_phase, quadrature) / len(values), 2)
        return result

    @staticmethod
    def _tone_pcm(
        *, frequency: float, duration: float, amplitude: int, sample_rate: int = 48_000
    ) -> bytes:
        values = array.array(
            "h",
            (
                round(amplitude * math.sin(2 * math.pi * frequency * index / sample_rate))
                for index in range(round(sample_rate * duration))
            ),
        )
        if sys.byteorder == "big":
            values.byteswap()
        return values.tobytes()

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
    def _direct_pose_delta(before, after) -> dict[str, float | None]:
        return {
            "x_m": DiagnosisChecks._finite_delta(before.x, after.x),
            "y_m": DiagnosisChecks._finite_delta(before.y, after.y),
            "heading_like": DiagnosisChecks._finite_delta(before.heading_like, after.heading_like),
        }

    @staticmethod
    def _finite_delta(start: object, end: object) -> float | None:
        try:
            start_value = float(start)
            end_value = float(end)
        except (TypeError, ValueError):
            return None
        if not math.isfinite(start_value) or not math.isfinite(end_value):
            return None
        return round(end_value - start_value, 4)

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
