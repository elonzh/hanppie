from __future__ import annotations

from hanppie.lab.app import AppConnection
from hanppie.lab.product import (
    RobotComponent,
    RobotModel,
    RobotProduct,
    decode_capabilities,
    decode_model,
    updated_product,
)
from hanppie.lab.protocol import DussFrame, build_duss


def frame(cmdid: int, payload: bytes, *, valid: bool = True, attr: int = 0xC0) -> DussFrame:
    return DussFrame(0, 0x28, 0x02, 1, attr, 0x3F, cmdid, payload, valid)


def test_product_type_requires_an_explicit_valid_response() -> None:
    assert decode_model(frame(0xFE, b"\x00\x01")) is RobotModel.ROBOMASTER_S1
    assert decode_model(frame(0xFE, b"\x00\x02")) is RobotModel.ROBOMASTER_EP
    assert decode_model(frame(0xFE, b"\x00\x07")) is RobotModel.UNKNOWN
    assert decode_model(frame(0xFE, b"\x00")) is None
    assert decode_model(frame(0x12, b"\x00\x01")) is None
    assert decode_model(frame(0xFE, b"\x00\x01", valid=False)) is None
    assert decode_model(frame(0xFE, b"\x00\x01", attr=0x80)) is None


def test_working_devices_are_independent_from_product_type() -> None:
    capabilities = decode_capabilities(
        frame(
            0x12,
            bytes.fromhex("0300030134121e03020000fe00777700"),
            attr=0x00,
        )
    )
    assert capabilities is not None
    assert RobotComponent.CHASSIS in capabilities.components
    assert RobotComponent.ARM in capabilities.components
    assert capabilities.working_devices[0].details == (0x1234,)
    assert capabilities.working_devices[1].details == (0, 0xFE)
    assert capabilities.unknown_device_ids == frozenset({0x7777})

    product = updated_product(
        RobotProduct(RobotModel.ROBOMASTER_EP),
        frame(0x12, bytes.fromhex("01000300"), attr=0x00),
    )
    assert product is not None
    assert product.model is RobotModel.ROBOMASTER_EP
    assert RobotComponent.CHASSIS in product.capabilities.components


def test_truncated_working_device_push_is_rejected() -> None:
    assert decode_capabilities(frame(0x12, bytes.fromhex("0100030100"), attr=0x00)) is None
    assert decode_capabilities(frame(0x12, b"", attr=0x00)) is None
    assert decode_capabilities(frame(0x12, b"\x00", attr=0x40)) is None


def test_app_connection_publishes_product_updates_and_clears_them_on_close() -> None:
    connection = AppConnection("192.0.2.10", "b6359877")
    updates: list[RobotProduct] = []
    connection.on("product", updates.append)

    connection._handle_packet(build_duss(0x28, 0x02, 0xC0, 0x3F, 0xFE, b"\x00\x02", 1))
    connection._handle_packet(build_duss(0x28, 0x02, 0x00, 0x3F, 0x12, b"\x01\x00\x03\x00", 2))

    assert connection.product.model is RobotModel.ROBOMASTER_EP
    assert RobotComponent.CHASSIS in connection.product.capabilities.components
    assert updates[-1] == connection.product

    connection.close()
    assert connection.product == RobotProduct()
