from __future__ import annotations

import pytest

from hanppie.lab.config import LabConfig
from hanppie.lab.protocol import (
    APP_CONNECTION_SETUP,
    AppEnvelope,
    build_duss,
    is_video_packet,
    next_session,
    normalize_appid,
    parse_duss_frames,
    parse_robot_broadcast,
)


def test_appid_and_configuration_validation() -> None:
    assert normalize_appid("B6359877") == "b6359877"
    with pytest.raises(ValueError, match="8 hexadecimal"):
        normalize_appid("not-an-id")
    with pytest.raises(ValueError, match="control_port"):
        LabConfig(control_port=0)
    with pytest.raises(ValueError, match="command_timeout"):
        LabConfig(command_timeout=-1)
    with pytest.raises(ValueError, match="telemetry_period"):
        LabConfig(telemetry_period=0)


def test_duss_frame_matches_captured_crc_vector() -> None:
    frame = build_duss(0x02, 0x09, 0x40, 0x3F, 0x57, b"", 10072)

    assert frame.hex() == "550d043302095827403f573c34"
    prefixed = parse_duss_frames(b"prefix" + frame)
    assert len(prefixed) == 1
    assert prefixed[0].offset == 6
    decoded = parse_duss_frames(frame)[0]
    assert decoded.valid
    assert (decoded.sender, decoded.receiver) == (0x02, 0x09)
    assert (decoded.cmdset, decoded.cmdid, decoded.payload) == (0x3F, 0x57, b"")

    damaged = bytearray(frame)
    damaged[-1] ^= 0xFF
    assert not parse_duss_frames(bytes(damaged))[0].valid


def test_outer_envelope_matches_captured_preconnect_and_direct_packet() -> None:
    frame = build_duss(0x02, 0x09, 0x40, 0x3F, 0x57, b"", 10072)
    envelope = AppEnvelope(session=bytes.fromhex("dc68"), tick=0x46D8)

    assert envelope.preconnect().hex() == (
        "3080dc6800000004d84664006400c005140000640064006400c005140000"
        "640014006400c00514000064000101040102"
    )
    assert envelope.wrap_direct(frame).hex() == (
        "2180dc68e04605b6d846e0460000000001010000550d043302095827403f573c34"
    )
    control = envelope.wrap_control(frame)
    assert len(control) == 34 + len(frame)
    assert control[2:4] == bytes.fromhex("dc68")
    assert int.from_bytes(control[32:34], "little") == len(frame)

    inbound = bytearray(28)
    inbound[2:4] = envelope.session
    inbound[4:6] = b"\x00\x00"
    inbound[16:18] = (123).to_bytes(2, "little")
    inbound[24:26] = (456).to_bytes(2, "little")
    envelope.observe(bytes(inbound))
    assert envelope.control_reference == 123
    assert envelope.direct_reference == 456

    streamed = bytearray(34) + bytearray(frame)
    streamed[2:4] = envelope.session
    streamed[10:12] = (789).to_bytes(2, "little")
    envelope.observe(bytes(streamed))
    assert envelope.control_tick == 789


def test_session_rollover_and_packet_classification() -> None:
    assert next_session(b"\xff\xff") == b"\x01\x00"
    with pytest.raises(ValueError, match="two bytes"):
        next_session(b"x")

    session = b"\x34\x12"
    video = bytearray(24)
    video[2:4] = session
    video[6] = 2
    assert is_video_packet(bytes(video), session)
    assert not is_video_packet(bytes(video[:20]), session)
    assert not is_video_packet(bytes(video), b"\x35\x12")


def test_robot_broadcast_decode() -> None:
    decoded = bytearray(24)
    decoded[:2] = b"\x5a\x5b"
    decoded[2] = 1
    decoded[6:10] = bytes((192, 168, 10, 42))
    decoded[10:16] = bytes.fromhex("001122334455")
    decoded[16:24] = b"b6359877"
    key = 7
    encoded = bytearray()
    for byte in decoded:
        encoded.append(byte ^ key)
        key = ((key + 7) ^ 178) & 0xFF

    broadcast = parse_robot_broadcast(bytes(encoded))
    assert broadcast is not None
    assert broadcast.robot_ip == "192.168.10.42"
    assert broadcast.robot_mac == "00:11:22:33:44:55"
    assert broadcast.appid == "b6359877"
    assert broadcast.pairing
    assert parse_robot_broadcast(b"short") is None


def test_setup_sequence_is_the_minimal_app_prefix() -> None:
    assert len(APP_CONNECTION_SETUP) == 19
    assert APP_CONNECTION_SETUP[0][4:6] == (0x00, 0x01)
    assert APP_CONNECTION_SETUP[-1][4:6] == (0x48, 0x03)
