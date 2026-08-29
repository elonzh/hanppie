from __future__ import annotations

import json

try:
    from importlib.resources import files
except ImportError:
    from importlib_resources import files


def test_packaged_adb_payload() -> None:
    payload = files("hanppie.payloads").joinpath("enable_adb_standalone.py.txt")
    source = payload.read_text(encoding="utf-8")
    assert "service.adb.tcp.port" in source
    assert "setprop" in source


def test_packaged_dji_configuration() -> None:
    resource = files("hanppie.resources").joinpath("dji.json")
    configuration = json.loads(resource.read_text(encoding="utf-8"))
    assert isinstance(configuration, dict)
