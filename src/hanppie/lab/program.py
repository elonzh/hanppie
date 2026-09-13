"""Build and upload RoboMaster Lab DSP program containers."""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass
from ftplib import FTP
from importlib.resources import files
from io import BytesIO
from xml.sax.saxutils import escape

from hanppie.lab.config import DEFAULT_CONFIG, LabConfig

DEFAULT_UPLOAD_NAME = "python_raw.dsp"


@dataclass(frozen=True)
class LabProgramIdentity:
    guid: str
    sign: str
    full_marker: int = 0x21
    guid_marker: int = 0x2D


def _cdata(value: str) -> str:
    return value.replace("]]>", "]]]]><![CDATA[>")


def render_bridge_source(config: LabConfig = DEFAULT_CONFIG) -> str:
    source = files("hanppie.lab").joinpath("bridge_payload.py.txt").read_text(encoding="utf-8")
    values = {
        "__CONTROL_PORT__": str(config.control_port),
        "__TELEMETRY_PORT__": str(config.telemetry_port),
        "__TELEMETRY_PERIOD__": repr(config.telemetry_period),
        "__COMMAND_TIMEOUT__": repr(config.command_timeout),
    }
    for marker, value in values.items():
        source = source.replace(marker, value)
    return source


def build_lab_program(
    python_source: str | None = None,
    *,
    config: LabConfig = DEFAULT_CONFIG,
    title: str = "Hanppie-Lab",
) -> tuple[str, LabProgramIdentity]:
    identity = LabProgramIdentity(guid=secrets.token_hex(16), sign=secrets.token_hex(8))
    source = render_bridge_source(config) if python_source is None else python_source
    dsp = (
        "<dji><attribute>"
        "<creation_date>2026/08/30</creation_date>"
        "<modify_time>2026/08/30</modify_time>"
        f"<sign>{identity.sign}</sign>"
        f"<guid>{identity.guid}</guid>"
        "<creator>Hanppie</creator>"
        "<firmware_version_dependency>00.00.0000</firmware_version_dependency>"
        f"<title>{escape(title)}</title>"
        "<code_type>python</code_type>"
        "<app_min_version></app_min_version><app_max_version></app_max_version>"
        "</attribute><audio-list /><code><python_code><![CDATA["
        f"{_cdata(source)}"
        "]]></python_code></code></dji>"
    )
    return dsp, identity


def upload_lab_program(
    robot_ip: str,
    dsp: str | bytes,
    *,
    filename: str = DEFAULT_UPLOAD_NAME,
    timeout: float = 10.0,
    ftp_factory: type[FTP] = FTP,
) -> str:
    payload = dsp.encode("utf-8") if isinstance(dsp, str) else dsp
    digest = hashlib.md5(payload).hexdigest()
    with ftp_factory() as ftp:
        ftp.connect(robot_ip, 21, timeout=timeout)
        ftp.login("anonymous", "")
        ftp.cwd("python")
        ftp.voidcmd("TYPE I")
        ftp.storbinary(f"STOR {filename}", BytesIO(payload))
    return digest
