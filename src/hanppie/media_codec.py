"""Compatibility layer for DJI's optional ``libmedia_codec`` extension.

DJI's Python SDK imports a native module that it does not publish for macOS.
This compatibility module implements the H.264 decoder interface with PyAV so
the official camera API can still be exercised on Apple Silicon.
"""

from __future__ import annotations

import importlib.util
import sys
import types


class H264Decoder:
    def __init__(self) -> None:
        self._codec = None

    def decode(self, data: bytes) -> list[tuple[bytes, int, int, int]]:
        if self._codec is None:
            import av

            self._codec = av.CodecContext.create("h264", "r")
        decoded: list[tuple[bytes, int, int, int]] = []
        for packet in self._codec.parse(data):
            for frame in self._codec.decode(packet):
                image = frame.to_ndarray(format="bgr24")
                height, width, channels = image.shape
                decoded.append((image.tobytes(), width, height, width * channels))
        return decoded


class OpusDecoder:
    def __init__(self) -> None:
        self._codec = None

    def decode(self, data: bytes) -> bytes:
        if self._codec is None:
            import av

            self._codec = av.CodecContext.create("opus", "r")
        pcm = bytearray()
        for packet in self._codec.parse(data):
            for frame in self._codec.decode(packet):
                array = frame.to_ndarray()
                pcm.extend(array.tobytes())
        return bytes(pcm)


def install_media_codec_compat() -> bool:
    """Install this module under DJI's expected name when no native build exists.

    Returns ``True`` when the compatibility module was installed and ``False``
    when a native ``libmedia_codec`` implementation is already importable.
    """

    if importlib.util.find_spec("libmedia_codec") is not None:
        return False
    module = types.ModuleType("libmedia_codec")
    module.H264Decoder = H264Decoder
    module.OpusDecoder = OpusDecoder
    sys.modules["libmedia_codec"] = module
    return True
