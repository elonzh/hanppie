"""Build and upload RoboMaster Lab DSP program containers."""

from __future__ import annotations

import hashlib
import secrets
from dataclasses import dataclass
from ftplib import FTP
from io import BytesIO
from xml.sax.saxutils import escape

from . import protocol

__all__ = [
    "DEFAULT_UPLOAD_NAME",
    "LabProgramIdentity",
    "build_lab_program",
    "upload_lab_program",
]

DEFAULT_UPLOAD_NAME = "python_raw.dsp"


@dataclass(frozen=True)
class LabProgramIdentity:
    guid: str
    sign: str
    full_marker: int = protocol.SCRIPT_CTRL_METADATA_FULL
    guid_marker: int = protocol.SCRIPT_CTRL_METADATA_GUID


def _cdata(value: str) -> str:
    return value.replace("]]>", "]]]]><![CDATA[>")


def build_lab_program(
    python_source: str,
    *,
    title: str = "Hanppie-Lab",
) -> tuple[str, LabProgramIdentity]:
    """Wrap Python source into the RoboMaster S1 Lab DSP XML container."""
    identity = LabProgramIdentity(guid=secrets.token_hex(16), sign=secrets.token_hex(8))
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
        f"{_cdata(python_source)}"
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
    """Upload a packaged DSP container to the robot's onboard FTP server."""
    payload = dsp.encode("utf-8") if isinstance(dsp, str) else dsp
    digest = hashlib.md5(payload).hexdigest()
    with ftp_factory() as ftp:
        ftp.connect(robot_ip, protocol.ROBOT_FTP_PORT, timeout=timeout)
        ftp.login("anonymous", "")
        ftp.cwd("python")
        ftp.voidcmd("TYPE I")
        ftp.storbinary(f"STOR {filename}", BytesIO(payload))
    return digest
