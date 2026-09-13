"""RoboMaster product and working-device messages carried by the App session."""

from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum, IntEnum

from hanppie.lab.protocol import DussFrame


class RobotModel(Enum):
    UNKNOWN = "unknown"
    ROBOMASTER_S1 = "robomaster_s1"
    ROBOMASTER_EP = "robomaster_ep"


class RobotComponent(IntEnum):
    IMAGE_TRANSMISSION = 0x0100
    CAMERA = 0x0104
    CHASSIS = 0x0300
    BATTERY = 0x030A
    ESC_0 = 0x0314
    ESC_1 = 0x0315
    ESC_2 = 0x0316
    ESC_3 = 0x0317
    SERVO_1 = 0x0318
    SERVO_2 = 0x0319
    SERVO_3 = 0x031A
    SERVO_4 = 0x031B
    ARM = 0x031E
    CLAW = 0x031F
    GIMBAL = 0x0400
    TOF_1 = 0x1201
    TOF_2 = 0x1202
    TOF_3 = 0x1203
    TOF_4 = 0x1204
    SENSOR_ADAPTER_1 = 0x1601
    SENSOR_ADAPTER_2 = 0x1602
    SENSOR_ADAPTER_3 = 0x1603
    SENSOR_ADAPTER_4 = 0x1604
    SENSOR_ADAPTER_5 = 0x1605
    SENSOR_ADAPTER_6 = 0x1606
    WATER_GUN = 0x1700
    INFRARED_GUN = 0x1701
    BACK_ARMOR = 0x1801
    FRONT_ARMOR = 0x1802
    LEFT_ARMOR = 0x1803
    RIGHT_ARMOR = 0x1804
    LEFT_HEAD_ARMOR = 0x1805
    RIGHT_HEAD_ARMOR = 0x1806


@dataclass(frozen=True)
class WorkingRobotDevice:
    device_id: int
    details: tuple[int, ...]

    @property
    def component(self) -> RobotComponent | None:
        try:
            return RobotComponent(self.device_id)
        except ValueError:
            return None


@dataclass(frozen=True)
class RobotCapabilities:
    working_devices: tuple[WorkingRobotDevice, ...] = ()

    @property
    def components(self) -> frozenset[RobotComponent]:
        return frozenset(
            component
            for device in self.working_devices
            if (component := device.component) is not None
        )

    @property
    def unknown_device_ids(self) -> frozenset[int]:
        return frozenset(
            device.device_id for device in self.working_devices if device.component is None
        )


@dataclass(frozen=True)
class RobotProduct:
    model: RobotModel = RobotModel.UNKNOWN
    capabilities: RobotCapabilities = RobotCapabilities()


def decode_model(frame: DussFrame) -> RobotModel | None:
    if (
        not frame.valid
        or frame.attr != 0xC0
        or (frame.cmdset, frame.cmdid) != (0x3F, 0xFE)
        or len(frame.payload) < 2
    ):
        return None
    return {
        1: RobotModel.ROBOMASTER_S1,
        2: RobotModel.ROBOMASTER_EP,
    }.get(frame.payload[1], RobotModel.UNKNOWN)


def decode_capabilities(frame: DussFrame) -> RobotCapabilities | None:
    if (
        not frame.valid
        or frame.attr != 0x00
        or (frame.cmdset, frame.cmdid) != (0x3F, 0x12)
        or not frame.payload
    ):
        return None
    expected_devices = frame.payload[0]
    devices: list[WorkingRobotDevice] = []
    cursor = 1
    for _ in range(expected_devices):
        if cursor + 3 > len(frame.payload):
            return None
        device_id = int.from_bytes(frame.payload[cursor : cursor + 2], "little")
        detail_count = frame.payload[cursor + 2]
        cursor += 3
        if cursor + detail_count * 2 > len(frame.payload):
            return None
        details = tuple(
            int.from_bytes(frame.payload[index : index + 2], "little")
            for index in range(cursor, cursor + detail_count * 2, 2)
        )
        cursor += detail_count * 2
        devices.append(WorkingRobotDevice(device_id, details))
    return RobotCapabilities(tuple(devices))


def updated_product(current: RobotProduct, frame: DussFrame) -> RobotProduct | None:
    model = decode_model(frame)
    capabilities = decode_capabilities(frame)
    if model is None and capabilities is None:
        return None
    updated = replace(
        current,
        model=current.model if model is None else model,
        capabilities=current.capabilities if capabilities is None else capabilities,
    )
    return updated if updated != current else None
