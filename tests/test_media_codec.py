from __future__ import annotations

import sys
import types

import pytest

from hanppie import media_codec


class FakeArray:
    shape = (2, 3, 3)

    def __init__(self, content: bytes) -> None:
        self.content = content

    def tobytes(self) -> bytes:
        return self.content


class FakeFrame:
    def __init__(self, content: bytes) -> None:
        self.content = content

    def to_ndarray(self, format: str | None = None) -> FakeArray:
        assert format in {None, "bgr24"}
        return FakeArray(self.content)


class FakeCodec:
    def parse(self, data: bytes) -> list[bytes]:
        return [data] if data else []

    def decode(self, packet: bytes) -> list[FakeFrame]:
        return [FakeFrame(packet + b"-decoded")]


@pytest.fixture
def fake_av(monkeypatch: pytest.MonkeyPatch) -> None:
    context = types.SimpleNamespace(create=lambda _name, _mode: FakeCodec())
    monkeypatch.setitem(sys.modules, "av", types.SimpleNamespace(CodecContext=context))


def test_installs_compat_module(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delitem(sys.modules, "libmedia_codec", raising=False)
    monkeypatch.setattr(media_codec.importlib.util, "find_spec", lambda _name: None)

    assert media_codec.install_media_codec_compat() is True
    assert sys.modules["libmedia_codec"].H264Decoder is media_codec.H264Decoder


def test_preserves_native_module(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(media_codec.importlib.util, "find_spec", lambda _name: object())
    assert media_codec.install_media_codec_compat() is False


def test_h264_decoder(fake_av: None) -> None:
    assert media_codec.H264Decoder().decode(b"frame") == [(b"frame-decoded", 3, 2, 9)]


def test_opus_decoder(fake_av: None) -> None:
    assert media_codec.OpusDecoder().decode(b"audio") == b"audio-decoded"
