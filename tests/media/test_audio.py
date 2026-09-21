from __future__ import annotations

import sys
from types import SimpleNamespace

import pytest

from hanppie.connection import AppConnectionInfo
from hanppie.media.audio import (
    AUDIO_PLAY_PAYLOAD,
    Audio,
    OpusDecoder,
    build_audio_block,
    build_audio_start_payload,
    build_audio_stop_payload,
    encode_speaker_pcm,
)

__all__: list[str] = []


class FakeConnection:
    def __init__(self) -> None:
        self.robot_ip = "192.0.2.10"
        self.connected = True
        self.info = AppConnectionInfo(self.robot_ip, "b6359877", "idle")
        self.calls: list[tuple[object, ...] | str] = []

    def send_duss(
        self,
        sender: int,
        receiver: int,
        attr: int,
        cmdset: int,
        cmdid: int,
        payload: bytes = b"",
    ) -> int:
        self.calls.append(("duss", sender, receiver, attr, cmdset, cmdid, payload.hex()))
        return len(self.calls)


def test_s1_opus_decoder_resamples_to_mono_s16(monkeypatch: pytest.MonkeyPatch) -> None:
    class Codec:
        def decode(self, _packet: object) -> list[object]:
            return [object()]

    class CodecContext:
        @staticmethod
        def create(name: str, mode: str) -> Codec:
            assert (name, mode) == ("opus", "r")
            return Codec()

    class Resampler:
        def __init__(self, **values: object) -> None:
            assert values == {"format": "s16", "layout": "mono", "rate": 48_000}

        def resample(self, _frame: object) -> list[object]:
            return [SimpleNamespace(samples=2, planes=[b"\x01\x00\x02\x00padding"])]

    fake_av = SimpleNamespace(
        CodecContext=CodecContext,
        AudioResampler=Resampler,
        Packet=lambda payload: payload,
    )
    monkeypatch.setitem(sys.modules, "av", fake_av)

    decoder = OpusDecoder()
    assert decoder.decode(b"") is None
    assert decoder.decode(b"opus") == b"\x01\x00\x02\x00"


def test_audio_uploads_encoded_clip_with_captured_sequence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    connection = FakeConnection()
    audio = Audio(connection)  # type: ignore[arg-type]
    delays: list[float] = []
    monkeypatch.setattr("hanppie.media.audio.time.sleep", delays.append)
    encoded = b"e" * 1000
    monkeypatch.setattr("hanppie.media.audio.encode_speaker_pcm", lambda _pcm: encoded)

    assert audio.play_pcm(b"pcm") == 2
    calls = [call for call in connection.calls if isinstance(call, tuple)]
    assert [(call[4], call[5]) for call in calls] == [
        (0x3F, 0x5F),
        (0x00, 0x09),
        (0x00, 0x09),
        (0x3F, 0x5F),
        (0x3F, 0xB3),
    ]
    assert bytes.fromhex(str(calls[0][6])) == build_audio_start_payload(2, 1000)
    assert bytes.fromhex(str(calls[1][6])) == build_audio_block(b"e" * 960, 0)
    assert bytes.fromhex(str(calls[2][6])) == build_audio_block(b"e" * 40, 1)
    assert bytes.fromhex(str(calls[3][6])) == build_audio_stop_payload(encoded)
    assert bytes.fromhex(str(calls[4][6])) == AUDIO_PLAY_PAYLOAD
    assert delays == [0.055, 0.006, 0.055, 0.107]


def test_speaker_pcm_is_length_prefixed_opus() -> None:
    encoded = encode_speaker_pcm(b"\x00" * 960)
    offset = 0
    packets: list[bytes] = []
    while offset < len(encoded):
        size = int.from_bytes(encoded[offset : offset + 2], "little")
        offset += 2
        packets.append(encoded[offset : offset + size])
        offset += size
    assert offset == len(encoded)
    assert packets
    assert all((packet[0] & 0xF8) == 0x28 for packet in packets)


def test_audio_metadata_and_block_headers_match_observed_packets() -> None:
    assert build_audio_start_payload(5, 4562).hex() == "00000001000500d2110000000000000000"
    assert build_audio_stop_payload(b"captured-pcm").hex() == ("023262e9161cd25bdf6e070a1a14b627c3")
    assert build_audio_block(b"audio", 1).hex() == "00010000000500617564696f"
