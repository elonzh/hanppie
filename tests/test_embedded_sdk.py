from __future__ import annotations

from importlib.resources import files

from hanppie import media_codec
from robomaster import config, conn, media, protocol, robot, version


def test_official_api_imports_are_preserved() -> None:
    assert robot.Robot.__name__ == "Robot"
    assert conn.SdkConnection.__name__ == "SdkConnection"
    assert protocol.Msg.__name__ == "Msg"
    assert config.ROBOT_IP_STR is None
    assert version.__version__ == "0.1.1.68"


def test_media_decoder_falls_back_to_bundled_pyav_adapter() -> None:
    assert media.libmedia_codec.H264Decoder is media_codec.H264Decoder
    assert media.libmedia_codec.OpusDecoder is media_codec.OpusDecoder


def test_upstream_license_and_provenance_are_packaged() -> None:
    package = files("robomaster")
    license_text = package.joinpath("LICENSE.txt").read_text(encoding="utf-8")
    upstream_text = package.joinpath("UPSTREAM.md").read_text(encoding="utf-8")

    assert "Apache License" in license_text
    assert "ff6646e115ab125af3207a4ed3df42cc76c795b2" in upstream_text
