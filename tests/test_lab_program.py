from __future__ import annotations

import ast
import hashlib
import xml.etree.ElementTree as ET

from hanppie.lab.config import LabConfig
from hanppie.lab.program import build_lab_program, render_bridge_source, upload_lab_program


class FakeFTP:
    instances: list[FakeFTP] = []

    def __init__(self) -> None:
        self.calls: list[object] = []
        self.payload = b""
        self.instances.append(self)

    def __enter__(self) -> FakeFTP:
        return self

    def __exit__(self, *_args: object) -> None:
        self.calls.append("close")

    def connect(self, host: str, port: int, timeout: float) -> None:
        self.calls.append(("connect", host, port, timeout))

    def login(self, username: str, password: str) -> None:
        self.calls.append(("login", username, password))

    def cwd(self, path: str) -> None:
        self.calls.append(("cwd", path))

    def voidcmd(self, command: str) -> None:
        self.calls.append(("voidcmd", command))

    def storbinary(self, command: str, source) -> None:
        self.calls.append(("storbinary", command))
        self.payload = source.read()


def test_bridge_source_is_self_contained_and_configured() -> None:
    source = render_bridge_source(
        LabConfig(
            control_port=41001,
            telemetry_port=41002,
            telemetry_period=0.2,
            command_timeout=0.4,
        )
    )

    assert "CONTROL_PORT = 41001" in source
    assert "TELEMETRY_PORT = 41002" in source
    assert "COMMAND_TIMEOUT = 0.4" in source
    assert "robomaster_lab_sdk" not in source
    assert "def start():" in source
    assert "any(" not in source
    assert "return max(" not in source
    ast.parse(source, feature_version=(3, 6))
    assert source.index('if not state["armed"]') < source.index(
        'if module in ("chassis", "gimbal")'
    )


def test_build_lab_program_generates_valid_python_dsp() -> None:
    dsp, identity = build_lab_program('print("]]>")', title="Hanppie & Test")
    root = ET.fromstring(dsp)
    attributes = root.find("attribute")

    assert attributes is not None
    assert attributes.findtext("guid") == identity.guid
    assert attributes.findtext("sign") == identity.sign
    assert attributes.findtext("title") == "Hanppie & Test"
    assert attributes.findtext("modify_time") == "2026/08/30"
    assert attributes.findtext("code_type") == "python"
    assert len(identity.guid) == 32
    assert len(identity.sign) == 16
    assert identity.full_marker == 0x21
    assert identity.guid_marker == 0x2D
    assert root.findtext("code/python_code") == 'print("]]>")'


def test_upload_lab_program_uses_anonymous_binary_ftp() -> None:
    FakeFTP.instances.clear()
    payload = "<dji>payload</dji>"

    digest = upload_lab_program(
        "192.0.2.10",
        payload,
        timeout=4.5,
        ftp_factory=FakeFTP,  # type: ignore[arg-type]
    )

    ftp = FakeFTP.instances[-1]
    assert digest == hashlib.md5(payload.encode()).hexdigest()
    assert ftp.payload == payload.encode()
    assert ftp.calls == [
        ("connect", "192.0.2.10", 21, 4.5),
        ("login", "anonymous", ""),
        ("cwd", "python"),
        ("voidcmd", "TYPE I"),
        ("storbinary", "STOR python_raw.dsp"),
        "close",
    ]
