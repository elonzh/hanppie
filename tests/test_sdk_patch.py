from __future__ import annotations

import subprocess
from pathlib import Path

import pytest

from hanppie import sdk_patch


def result(
    stdout: str = "", stderr: str = "", returncode: int = 0
) -> subprocess.CompletedProcess[str]:
    return subprocess.CompletedProcess([], returncode, stdout, stderr)


class FakeDevice:
    def __init__(self) -> None:
        self.commands: list[tuple[str, ...]] = []
        self.mounts = ""
        self.hashes = dict(sdk_patch.STOCK_HASHES)

    def run(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        self.commands.append(args)
        if args == ("get-state",):
            return result("device\n")
        return result()

    def shell(self, *args: str, check: bool = True) -> str:
        self.commands.append(("shell", *args))
        if args == ("id",):
            return "uid=0(root) gid=0(root)"
        if args == ("cat", "/proc/mounts"):
            return self.mounts
        if args[:2] == ("/system/xbin/busybox", "sha256sum"):
            path = args[2]
            if path.startswith(sdk_patch.REMOTE_STAGE):
                digest = sdk_patch.PATCH_HASHES[Path(path).name]
            else:
                digest = self.hashes[path]
            return f"{digest}  {path}"
        if args[:3] == ("mount", "-o", "bind"):
            source, target = args[3:]
            self.hashes[target] = sdk_patch.PATCH_HASHES[Path(source).name]
            self.mounts += f"{source} {target} none rw 0 0\n"
            return ""
        if args and args[0] == "getprop":
            return "running"
        if args == ("netstat", "-anu"):
            return "udp 0 0 0.0.0.0:30030 0.0.0.0:*"
        return ""


def test_sha256_file(tmp_path: Path) -> None:
    sample = tmp_path / "sample"
    sample.write_bytes(b"hanppie")
    assert sdk_patch.sha256_file(sample) == (
        "da50fc313a0effcf90a0330fc3af3b2f49756667dbd5e24da25b377b54c16139"
    )


def test_device_wraps_adb_and_reports_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[list[str]] = []

    def run(command: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        calls.append(command)
        return result(stderr="denied", returncode=1)

    monkeypatch.setattr(sdk_patch.subprocess, "run", run)
    device = sdk_patch.Device("adb-test", "robot:5555")

    assert device.run("get-state", check=False).returncode == 1
    with pytest.raises(RuntimeError, match="adb-test -s robot:5555 shell id"):
        device.shell("id")
    assert calls[0] == ["adb-test", "-s", "robot:5555", "get-state"]


def test_preflight_and_status(capsys: pytest.CaptureFixture[str]) -> None:
    device = FakeDevice()

    sdk_patch.preflight(device)
    sdk_patch.show_status(device)

    output = capsys.readouterr().out
    assert "uid=0(root)" in output
    assert "bind_mounts=none" in output
    assert "udp_30030_listening=True" in output


def test_preflight_rejects_non_root() -> None:
    device = FakeDevice()
    device.shell = lambda *args, **kwargs: "uid=2000(shell)"  # type: ignore[method-assign]
    with pytest.raises(RuntimeError, match="not root"):
        sdk_patch.preflight(device)


def test_restore_mounts_only_unmounts_present_targets() -> None:
    device = FakeDevice()
    device.mounts = "/data/dji.json /system/etc/dji.json none rw 0 0"

    sdk_patch.restore_mounts(device)

    assert ("shell", "umount", "/system/etc/dji.json") in device.commands
    assert ("shell", "umount", "/system/bin/dji_hdvt_uav") not in device.commands


def test_enable_rejects_unreviewed_patch(tmp_path: Path) -> None:
    for name in sdk_patch.PATCH_HASHES:
        (tmp_path / name).write_bytes(b"unreviewed")

    with pytest.raises(RuntimeError, match="unexpected SHA-256"):
        sdk_patch.enable(FakeDevice(), tmp_path)


def test_enable_stages_and_mounts_audited_patch(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    for name in sdk_patch.PATCH_HASHES:
        (tmp_path / name).write_bytes(name.encode())
    monkeypatch.setattr(
        sdk_patch,
        "sha256_file",
        lambda path: sdk_patch.PATCH_HASHES[path.name],
    )
    device = FakeDevice()

    sdk_patch.enable(device, tmp_path)

    assert device.hashes["/system/etc/dji.json"] == sdk_patch.PATCH_HASHES["dji.json"]
    assert device.hashes["/system/bin/dji_hdvt_uav"] == sdk_patch.PATCH_HASHES["dji_hdvt_uav"]
    assert "official_sdk_patch=enabled_volatile" in capsys.readouterr().out


def test_restore_stops_unmounts_and_restarts(capsys: pytest.CaptureFixture[str]) -> None:
    device = FakeDevice()

    sdk_patch.restore(device)

    assert ("shell", "stop", "dji_sys") in device.commands
    assert ("shell", "start", "dji_vision") in device.commands
    assert "official_sdk_patch=restored_stock" in capsys.readouterr().out


def test_main_status_and_error(monkeypatch: pytest.MonkeyPatch) -> None:
    device = FakeDevice()
    monkeypatch.setattr(sdk_patch, "Device", lambda _adb, _target: device)
    assert sdk_patch.main(["--target", "robot:5555", "status"]) == 0

    monkeypatch.setattr(
        sdk_patch, "preflight", lambda _device: (_ for _ in ()).throw(ValueError("bad"))
    )
    assert sdk_patch.main(["--target", "robot:5555", "status"]) == 1
