"""RoboMaster bidirectional audio transport, currently verified on S1."""

from __future__ import annotations

import hashlib
import time
from collections.abc import Iterable

from hanppie.lab.app import AppConnection

MICROPHONE_SAMPLE_RATE = 48_000
MICROPHONE_CHANNELS = 1
MICROPHONE_SAMPLE_WIDTH = 2
SPEAKER_SAMPLE_RATE = 12_000
SPEAKER_SAMPLE_WIDTH = 2
SPEAKER_FRAME_SAMPLES = 240
SPEAKER_FRAME_BYTES = SPEAKER_FRAME_SAMPLES * SPEAKER_SAMPLE_WIDTH
AUDIO_TRANSFER_CHUNK_BYTES = 960
AUDIO_TRANSFER_ID = bytes.fromhex("00000100")
AUDIO_PACKET_INTERVAL_SECONDS = 0.006
AUDIO_START_DELAY_SECONDS = 0.055
AUDIO_STOP_DELAY_SECONDS = 0.055
AUDIO_PLAY_DELAY_SECONDS = 0.107
AUDIO_PLAY_PAYLOAD = bytes.fromhex("0b040000010001000001")


def build_audio_start_payload(packet_count: int, total_bytes: int) -> bytes:
    """Build the metadata command that starts one encoded audio upload."""

    if not 1 <= packet_count <= 0xFFFF:
        raise ValueError("packet_count must be between 1 and 65535")
    if not 1 <= total_bytes <= 0xFFFF:
        raise ValueError("total_bytes must be between 1 and 65535")
    return (
        b"\x00"
        + AUDIO_TRANSFER_ID
        + packet_count.to_bytes(2, "little")
        + total_bytes.to_bytes(2, "little")
        + b"\x00" * 8
    )


def build_audio_stop_payload(encoded: bytes) -> bytes:
    """Build the commit command for one encoded audio upload."""

    return b"\x02" + hashlib.md5(encoded).digest()


def encode_speaker_pcm(pcm: bytes) -> bytes:
    """Encode 12 kHz mono signed 16-bit PCM as length-prefixed Opus packets."""

    if not pcm:
        return b""
    if len(pcm) % SPEAKER_SAMPLE_WIDTH:
        raise ValueError("PCM input must contain complete signed 16-bit samples")

    import av

    pending = bytearray(pcm)
    remainder = len(pending) % SPEAKER_FRAME_BYTES
    if remainder:
        pending.extend(b"\x00" * (SPEAKER_FRAME_BYTES - remainder))

    codec = av.CodecContext.create("opus", "w")
    codec.sample_rate = SPEAKER_SAMPLE_RATE
    codec.layout = "mono"
    codec.format = "s16"
    codec.bit_rate = 10_000
    codec.options = {
        "application": "voip",
        "frame_duration": "20",
        "vbr": "on",
    }

    encoded = bytearray()
    for offset in range(0, len(pending), SPEAKER_FRAME_BYTES):
        frame = av.AudioFrame(format="s16", layout="mono", samples=SPEAKER_FRAME_SAMPLES)
        frame.sample_rate = SPEAKER_SAMPLE_RATE
        frame.planes[0].update(pending[offset : offset + SPEAKER_FRAME_BYTES])
        _append_encoded_packets(encoded, codec.encode(frame))
    _append_encoded_packets(encoded, codec.encode(None))
    return bytes(encoded)


def _append_encoded_packets(encoded: bytearray, packets: Iterable[object]) -> None:
    for packet in packets:
        payload = bytes(packet)
        encoded.extend(len(payload).to_bytes(2, "little"))
        encoded.extend(payload)


def build_audio_block(payload: bytes, index: int) -> bytes:
    """Wrap one encoded transfer chunk in the S1-verified audio block header."""

    if not 0 <= index <= 0xFFFF:
        raise ValueError("audio block index must be between 0 and 65535")
    return index.to_bytes(2, "big") + b"\x00\x00\x00" + len(payload).to_bytes(2, "little") + payload


class OpusDecoder:
    """Decode one S1-verified microphone packet to 48 kHz mono signed 16-bit PCM."""

    def __init__(self) -> None:
        self._codec = None
        self._resampler = None

    def decode(self, payload: bytes) -> bytes | None:
        if not payload:
            return None
        if self._codec is None or self._resampler is None:
            import av

            self._codec = av.CodecContext.create("opus", "r")
            self._codec.sample_rate = MICROPHONE_SAMPLE_RATE
            self._codec.layout = "mono"
            self._resampler = av.AudioResampler(
                format="s16",
                layout="mono",
                rate=MICROPHONE_SAMPLE_RATE,
            )

        import av

        try:
            decoded = self._codec.decode(av.Packet(payload))
        except Exception:
            return None
        chunks: list[bytes] = []
        for frame in decoded:
            try:
                converted = self._resampler.resample(frame)
            except Exception:
                continue
            frames = converted if isinstance(converted, list) else [converted]
            for output in frames:
                if output is None or not output.planes:
                    continue
                byte_count = int(output.samples) * MICROPHONE_CHANNELS * MICROPHONE_SAMPLE_WIDTH
                chunks.append(bytes(output.planes[0])[:byte_count])
        return b"".join(chunks) or None


class LabAudio:
    """Upload 12 kHz mono signed 16-bit PCM through the S1-verified speaker route."""

    def __init__(self, connection: AppConnection) -> None:
        self._connection = connection

    def play_pcm(self, pcm: bytes) -> int:
        """Encode, upload, commit, and play one PCM clip; return packet count."""

        encoded = encode_speaker_pcm(pcm)
        if not encoded:
            return 0
        chunks = tuple(
            encoded[offset : offset + AUDIO_TRANSFER_CHUNK_BYTES]
            for offset in range(0, len(encoded), AUDIO_TRANSFER_CHUNK_BYTES)
        )
        self._connection.send_duss(
            0x02,
            0x09,
            0x40,
            0x3F,
            0x5F,
            build_audio_start_payload(len(chunks), len(encoded)),
        )
        time.sleep(AUDIO_START_DELAY_SECONDS)
        for index, chunk in enumerate(chunks):
            self._connection.send_duss(
                0x02,
                0x09,
                0x00,
                0x00,
                0x09,
                build_audio_block(chunk, index),
            )
            if index + 1 < len(chunks):
                time.sleep(AUDIO_PACKET_INTERVAL_SECONDS)
        time.sleep(AUDIO_STOP_DELAY_SECONDS)
        self._connection.send_duss(
            0x02,
            0x09,
            0x40,
            0x3F,
            0x5F,
            build_audio_stop_payload(encoded),
        )
        time.sleep(AUDIO_PLAY_DELAY_SECONDS)
        self._connection.send_duss(0x02, 0x09, 0x40, 0x3F, 0xB3, AUDIO_PLAY_PAYLOAD)
        return len(chunks)

    def close(self) -> None:
        return None
