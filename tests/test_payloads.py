from __future__ import annotations

from importlib.resources import files


def test_packaged_adb_payload() -> None:
    payload = files("hanppie.payloads").joinpath("enable_adb_standalone.py.txt")
    source = payload.read_text(encoding="utf-8")
    assert "service.adb.tcp.port" in source
    assert "setprop" in source
