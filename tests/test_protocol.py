from __future__ import annotations

import pytest

from hanppie.protocol import (
    APP_CONNECTION_SETUP,
    AppEnvelope,
    build_duss,
    crc8,
    crc16,
    is_video_packet,
    next_session,
    normalize_appid,
    parse_duss_frames,
    parse_robot_broadcast,
    xor_header,
)

__all__: list[str] = []


def test_crc_known_vectors() -> None:
    assert crc8([0x55, 0x0D, 0x04]) == 0x33
    assert crc8(bytes([0x55, 0x0D, 0x04])) == 0x33
    frame = build_duss(0x02, 0x09, 0x40, 0x3F, 0x57, b"", 10072)
    # Whole frame excluding last 2 bytes CRC16
    assert crc16(frame[:-2]) == int.from_bytes(frame[-2:], "little")


def test_appid_validation() -> None:
    assert normalize_appid("B6359877") == "b6359877"
    with pytest.raises(ValueError, match="8 hexadecimal"):
        normalize_appid("not-an-id")


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


@pytest.mark.parametrize("valid_header", [False, True])
def test_invalid_outer_header_candidate_does_not_hide_real_frame(valid_header: bool) -> None:
    # S1 video transfer: an outer checksum of 0x55 falsely claimed a 64-byte frame.
    frame = build_duss(1, 2, 0, 0, 0x27, bytes(80), 123)
    prefix = bytearray(20)
    prefix[7:10] = b"\x55\x40\x04"
    if valid_header:
        prefix[10] = crc8(prefix[7:10])
    decoded = parse_duss_frames(bytes(prefix) + frame)
    assert any(not item.valid and item.offset == 7 for item in decoded)
    valid = [item for item in decoded if item.valid]
    assert len(valid) == 1
    assert (valid[0].offset, valid[0].cmdid, valid[0].sequence, valid[0].payload) == (
        20,
        0x27,
        123,
        bytes(80),
    )


def test_valid_frame_payload_is_not_rescanned() -> None:
    inner = build_duss(9, 2, 0, 0x3F, 0x70, b"", 5)
    outer = build_duss(1, 2, 0, 0, 0x27, inner, 6)
    decoded = parse_duss_frames(outer)
    assert len(decoded) == 1
    assert decoded[0].valid
    assert (decoded[0].cmdid, decoded[0].payload) == (0x27, inner)


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
    inbound[6] = 1
    inbound[16:18] = (123).to_bytes(2, "little")
    inbound[24:26] = (456).to_bytes(2, "little")
    envelope.observe(bytes(inbound))
    assert envelope.control_reference == 123
    assert envelope.direct_reference == 456

    streamed = bytearray(34) + bytearray(frame)
    streamed[2:4] = envelope.session
    streamed[6] = 1
    streamed[10:12] = (789).to_bytes(2, "little")
    envelope.observe(bytes(streamed))
    assert envelope.control_tick == 789


def _reliable_packet(tick: int, base: int = 8, session: bytes = b"\x34\x12") -> bytes:
    packet = bytearray(
        AppEnvelope(session=session, tick=base).wrap_direct(build_duss(9, 2, 0, 0, 0x70, b"", 0))
    )
    packet[4:6] = tick.to_bytes(2, "little")
    packet[6] = 3
    packet[7] = xor_header(packet[:7])
    packet[8:10] = base.to_bytes(2, "little")
    packet[10:12] = tick.to_bytes(2, "little")
    return bytes(packet)


def test_reliable_receive_window_acknowledges_all_one_hundred_native_packets() -> None:
    envelope = AppEnvelope(session=b"\x34\x12", tick=80)
    for tick in range(16, 809, 8):
        envelope.observe(_reliable_packet(tick))
    control = envelope.wrap_control(b"")
    assert int.from_bytes(control[16:18], "little") == 808
    assert int.from_bytes(control[18:20], "little") == 808
    assert int.from_bytes(control[24:26], "little") == 80
    assert int.from_bytes(control[26:28], "little") == 80
    assert int.from_bytes(envelope.wrap_direct(b"")[4:6], "little") == 88


def test_reliable_receive_window_waits_for_gaps_and_ignores_duplicates_and_other_sessions() -> None:
    envelope = AppEnvelope(session=b"\x34\x12", tick=80)
    envelope.observe(_reliable_packet(16))
    envelope.observe(_reliable_packet(32))
    assert envelope.wrap_control(b"")[16:20] == b"\x10\x00" * 2
    envelope.observe(_reliable_packet(24))
    assert envelope.wrap_control(b"")[16:20] == b"\x20\x00" * 2
    envelope.observe(_reliable_packet(16))
    envelope.observe(_reliable_packet(32))
    envelope.observe(_reliable_packet(40, session=b"\x78\x56"))
    assert envelope.wrap_control(b"")[16:20] == b"\x20\x00" * 2


def test_reliable_tick_zero_wraps_without_reading_payload_as_control_table() -> None:
    envelope = AppEnvelope(session=b"\x34\x12", tick=80)
    packet = bytearray(_reliable_packet(0, base=65528))
    packet[24:28] = b"\xd2\x04\x2e\x16"
    envelope.observe(bytes(packet))
    assert envelope.wrap_control(b"")[16:20] == b"\x00" * 4
    envelope.observe(_reliable_packet(8, base=65528))
    assert envelope.wrap_control(b"")[16:20] == b"\x08\x00" * 2
    assert envelope.direct_reference == 80


def test_control_table_does_not_replace_a_contiguous_receive_acknowledgement() -> None:
    envelope = AppEnvelope(session=b"\x34\x12", tick=80)
    for tick in range(16, 33, 8):
        envelope.observe(_reliable_packet(tick))
    packet = bytearray(34)
    packet[2:4] = envelope.session
    packet[6] = 1
    for reference in (24, 8, 40):
        packet[16:18] = reference.to_bytes(2, "little")
        envelope.observe(bytes(packet))
        assert envelope.wrap_control(b"")[16:20] == b"\x20\x00" * 2


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
