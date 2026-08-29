#!/usr/bin/env python3
"""Enable, inspect, or restore the volatile S1-to-EP SDK compatibility patch.

The tool never overwrites /system.  ``enable`` stages two audited files under
/data and bind-mounts them over the running firmware paths.  ``restore`` removes
those mounts.  A reboot also restores the stock state.
"""

from __future__ import annotations

import argparse
import hashlib
import pathlib
import subprocess
import sys

STOCK_HASHES = {
    "/system/etc/dji.json": "63db3ea0b9931cc519255265be66af35c93623c636a91ed59c3ff10280fb8ed8",
    "/system/bin/dji_hdvt_uav": "19d957e93672ce105d09eec509ec2fb873c8eb69a9287e0a505a6438538b2b38",
}
PATCH_HASHES = {
    "dji.json": "19db1dfcab6d21840735a69210d5c4dd0083759c2b02fbd708fd0f9892d98307",
    "dji_hdvt_uav": "da1ccac46c2c56a70af21f6b0da747e84040ba2ea850a29e07af27bc3c21c90f",
}
REMOTE_STAGE = "/data/s1_sdk_test"
SERVICES = ("dji_sys", "dji_hdvt_uav", "dji_vision")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb", help="adb executable")
    parser.add_argument("--target", required=True, help="explicit ADB serial, usually S1_IP:5555")
    subparsers = parser.add_subparsers(dest="action", required=True)
    subparsers.add_parser("status")
    enable = subparsers.add_parser("enable")
    enable.add_argument("--hack-dir", type=pathlib.Path, required=True)
    subparsers.add_parser("restore")
    return parser.parse_args(argv)


class Device:
    def __init__(self, adb: str, target: str) -> None:
        self._prefix = [adb, "-s", target]

    def run(self, *args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
        command = [*self._prefix, *args]
        result = subprocess.run(command, text=True, capture_output=True)
        if check and result.returncode != 0:
            raise RuntimeError(
                f"command failed ({result.returncode}): {' '.join(command)}\n"
                f"stdout: {result.stdout.strip()}\nstderr: {result.stderr.strip()}"
            )
        return result

    def shell(self, *args: str, check: bool = True) -> str:
        return self.run("shell", *args, check=check).stdout.strip()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def remote_hash(device: Device, path: str) -> str:
    output = device.shell("/system/xbin/busybox", "sha256sum", path)
    return output.split()[0]


def preflight(device: Device) -> None:
    state = device.run("get-state").stdout.strip()
    if state != "device":
        raise RuntimeError(f"ADB target is not ready: {state!r}")
    identity = device.shell("id")
    if "uid=0(root)" not in identity:
        raise RuntimeError(f"ADB shell is not root: {identity}")
    print(f"device_identity={identity}")


def mount_lines(device: Device) -> list[str]:
    output = device.shell("cat", "/proc/mounts")
    return [
        line
        for line in output.splitlines()
        if "/system/etc/dji.json" in line or "/system/bin/dji_hdvt_uav" in line
    ]


def show_status(device: Device) -> None:
    for path in STOCK_HASHES:
        print(f"sha256 {path} {remote_hash(device, path)}")
    mounts = mount_lines(device)
    print("bind_mounts=" + (" | ".join(mounts) if mounts else "none"))
    for service in SERVICES:
        print(f"service_{service}={device.shell('getprop', f'init.svc.{service}')}")
    sockets = device.shell("netstat", "-anu", check=False)
    listening = any(":30030" in line for line in sockets.splitlines())
    print(f"udp_30030_listening={listening}")


def stop_services(device: Device) -> None:
    for service in SERVICES:
        device.shell("stop", service)


def start_services(device: Device) -> None:
    for service in SERVICES:
        device.shell("start", service)


def restore_mounts(device: Device) -> None:
    mounts = mount_lines(device)
    for target in ("/system/bin/dji_hdvt_uav", "/system/etc/dji.json"):
        if not any(target in line for line in mounts):
            continue
        result = device.run("shell", "umount", target, check=False)
        output = "\n".join(part.strip() for part in (result.stdout, result.stderr) if part.strip())
        if result.returncode != 0 or "failed:" in output.lower():
            raise RuntimeError(f"could not unmount {target}: {output or result.returncode}")


def enable(device: Device, hack_dir: pathlib.Path) -> None:
    sources = {name: hack_dir / name for name in PATCH_HASHES}
    for name, path in sources.items():
        if not path.is_file():
            raise RuntimeError(f"missing patch file: {path}")
        actual = sha256_file(path)
        expected = PATCH_HASHES[name]
        if actual != expected:
            raise RuntimeError(f"unexpected SHA-256 for {path}: {actual}, expected {expected}")

    current = {path: remote_hash(device, path) for path in STOCK_HASHES}
    allowed = set(STOCK_HASHES.values()) | set(PATCH_HASHES.values())
    unexpected = {path: digest for path, digest in current.items() if digest not in allowed}
    if unexpected:
        raise RuntimeError(
            f"device files do not match the audited stock/patch hashes: {unexpected}"
        )

    if mount_lines(device) and all(value in PATCH_HASHES.values() for value in current.values()):
        print("official_sdk_patch=already_enabled")
        show_status(device)
        return

    device.shell("mkdir", "-p", REMOTE_STAGE)
    for name, path in sources.items():
        device.run("push", str(path), f"{REMOTE_STAGE}/{name}")
    device.shell("chmod", "0644", f"{REMOTE_STAGE}/dji.json")
    device.shell("chmod", "0755", f"{REMOTE_STAGE}/dji_hdvt_uav")
    for name, expected in PATCH_HASHES.items():
        actual = remote_hash(device, f"{REMOTE_STAGE}/{name}")
        if actual != expected:
            raise RuntimeError(f"staged {name} failed SHA-256 verification: {actual}")

    stop_services(device)
    try:
        restore_mounts(device)
        device.shell("mount", "-o", "bind", f"{REMOTE_STAGE}/dji.json", "/system/etc/dji.json")
        device.shell(
            "mount", "-o", "bind", f"{REMOTE_STAGE}/dji_hdvt_uav", "/system/bin/dji_hdvt_uav"
        )
    except Exception:
        restore_mounts(device)
        start_services(device)
        raise
    start_services(device)
    print("official_sdk_patch=enabled_volatile")
    show_status(device)


def restore(device: Device) -> None:
    stop_services(device)
    restore_mounts(device)
    start_services(device)
    current = {path: remote_hash(device, path) for path in STOCK_HASHES}
    if current != STOCK_HASHES:
        raise RuntimeError(f"stock hashes were not restored: {current}")
    print("official_sdk_patch=restored_stock")
    show_status(device)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    device = Device(args.adb, args.target)
    try:
        preflight(device)
        if args.action == "status":
            show_status(device)
        elif args.action == "enable":
            enable(device, args.hack_dir.resolve())
        elif args.action == "restore":
            restore(device)
        return 0
    except Exception as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
