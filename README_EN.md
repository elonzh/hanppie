# Hanppie

[简体中文](./README.md) | [English](./README_EN.md)

[![CI](https://github.com/elonzh/hanppie/actions/workflows/ci.yml/badge.svg)](https://github.com/elonzh/hanppie/actions/workflows/ci.yml)
[![Python](https://img.shields.io/badge/Python-3.8%2B-blue)](https://www.python.org/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](./LICENSE)

Hanppie is an open preservation and computer-programming toolkit for the DJI RoboMaster S1. Its goal is to restore auditable and reversible connectivity, programming, telemetry, and remote-control capabilities without depending on the mobile app.

The project is currently Alpha software. It is independent from and not endorsed by DJI.

## Verified on a physical S1

Tested firmware: `00.06.0521`.

- App/Lab-compatible computer connection, on-device Lab Python, and attitude telemetry;
- `1280×720` H.264 video through the App/Lab media path;
- temporary USB/TCP root ADB enabled through Lab;
- temporary restoration of DJI's official SDK service with reversible bind mounts;
- official Python SDK handshake, `Robot.initialize()`, firmware version, serial number, mode, gimbal angle, and LED control;
- complete status, restore, and re-enable round trip for the SDK patch.

Known limitations: the official SDK camera request is rejected by the S1, and several EP DDS topics continuously return zero. The current recommendation is to use the official SDK for control and verified telemetry, and the App/Lab backend for video and S1-specific data. Physical chassis motion, gimbal motion, and the launcher have not yet been tested under the required safety conditions.

The long-lived technical source of truth is [RoboMaster S1 Technical Architecture](./docs/architecture.md) (Chinese). Reproducible evidence is recorded in the [physical-device debug log](./docs/s1-live-debug-2026-08-29.md), while the earlier [research report](./docs/robomaster-s1-revival-report.md) covers S.BUS, SocketCAN, vcan, ROS 2, and alternative approaches.

## Safety warning

The ADB bootstrap exposes an **unauthenticated root shell** on TCP port 5555. Use it only briefly on a trusted, isolated network, and reboot the S1 after maintenance. Never configure public port forwarding for the robot.

Before the first motion test:

- lift the wheels off the ground or clear a sufficiently large test area;
- remove all gel beads and disable launcher support separately;
- implement a dead-man control, speed limits, and zero-on-timeout behavior;
- keep physical access to the power button.

## Installation

The core package requires Python 3.8+ and [uv](https://docs.astral.sh/uv/). [Task](https://taskfile.dev/) is the optional unified task runner.

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
uv run hanppie --help
```

The two robot backends install conflicting RoboMaster Python packages and therefore cannot be active together. Compatibility follows DJI's actual distributions:

| Backend | Python | Installation |
| --- | --- | --- |
| DJI official SDK | 3.8 | Official `robomaster==0.1.1.68` wheel on Linux x86_64 or Windows x86_64 |
| DJI official SDK | 3.8+ | Pinned official source checkout on macOS or Python 3.9+ |
| S1 App/Lab SDK | 3.10+ | Pinned community package |

DJI published CPython 3.6–3.8 Linux/Windows x86_64 wheels only, with no source distribution or macOS wheel. Hanppie uses Python 3.8 as the common minimum supported by the official release and the modern toolchain. Its PyAV media adapter and Python 3.13+ `audioop` compatibility dependency allow the pinned official source to run on newer Python versions and macOS.

```bash
# DJI official backend; supported Python 3.8 platforms install the official wheel.
task sync:official

# macOS and Python 3.9+ also need a pinned official source checkout.
git clone https://github.com/dji-sdk/RoboMaster-SDK.git ../RoboMaster-SDK
git -C ../RoboMaster-SDK checkout ff6646e115ab125af3207a4ed3df42cc76c795b2
export HANPPIE_OFFICIAL_SDK_PATH=../RoboMaster-SDK

# S1 App/Lab-compatible backend (Python 3.10+).
task sync:lab
```

`uv sync` reproduces the locked environment. The `official` and `lab` extras are declared mutually exclusive so that one backend cannot silently overwrite the other.

## Command line

```text
hanppie sdk                 inspect, enable, or restore the temporary SDK patch
hanppie adb-enable          temporarily enable root ADB through App/Lab
hanppie probe-connection    test the low-level official SDK handshake
hanppie probe-official      read identity and basic official-SDK telemetry
hanppie probe-telemetry     sample the official-SDK topic capability matrix
hanppie probe-led           verify hardware control without mechanical movement
hanppie probe-camera        reproduce the official-camera compatibility boundary
hanppie probe-lab           run a Lab program and sample attitude telemetry
hanppie probe-video         decode App/Lab video
```

Each command provides its own help:

```bash
uv run hanppie sdk --help
uv run hanppie adb-enable --help
```

### Restore the official SDK after a reboot

A reboot removes ADB and all SDK bind mounts. First select the Lab backend and enable ADB:

```bash
export S1_IP="192.168.x.x"
export S1_APPID="your-8-character-appid"
export S1_SERIAL="your-s1-serial"

task sync:lab
uv run hanppie adb-enable \
  --robot-ip "$S1_IP" \
  --appid "$S1_APPID" \
  --settle-seconds 8
```

After confirming `adb devices -l`, select the official backend and enable the audited community patch:

```bash
task sync:official
export HANPPIE_OFFICIAL_SDK_PATH=../RoboMaster-SDK
uv run hanppie sdk \
  --target "$S1_IP:5555" \
  enable \
  --hack-dir /path/to/audited/s1_sdk_hack

uv run hanppie probe-official \
  --robot-ip "$S1_IP" \
  --sn "$S1_SERIAL"
```

Restore the stock runtime without rebooting:

```bash
uv run hanppie sdk \
  --target "$S1_IP:5555" \
  restore
```

`enable` accepts only the fixed SHA-256 values of the audited files, never overwrites `/system`, and verifies every upload again on the device.

## Package layout

```text
src/hanppie/
├── cli.py              # unified CLI
├── sdk_patch.py        # ADB and temporary SDK-service management
├── adb_bootstrap.py    # App/Lab ADB bootstrap
├── media_codec.py      # DJI media-interface adapter for macOS
├── probes/             # safe, single-purpose physical-device probes
├── payloads/           # minimal temporary payloads uploaded to the S1
├── runtime/            # recovered S1 Lab/DUSS runtime
└── resources/          # non-executable device-configuration references
```

`runtime` exists for protocol research and offline testing. Host-side control should enter through the `hanppie` CLI and must not rely on the historical flat import layout.

## Development toolchain

```bash
task sync       # synchronize the uv development environment
task format     # format and apply safe Ruff fixes
task lint       # run Ruff formatting and lint checks
task test       # run pytest with coverage
task check      # run lint and tests
task build      # build the sdist and wheel
task hooks      # install prek pre-commit and pre-push hooks
task prek       # run hooks against all tracked files
```

Configuration lives in [`pyproject.toml`](./pyproject.toml), [`Taskfile.yml`](./Taskfile.yml), and [`prek.toml`](./prek.toml). CI uses the same lock file and commands.

Read [`CONTRIBUTING.md`](./CONTRIBUTING.md) before contributing. Architecture or capability changes must update [`docs/architecture.md`](./docs/architecture.md). See [`SECURITY.md`](./SECURITY.md) for security reporting and [`NOTICE.md`](./NOTICE.md) for provenance and trademark information.

## Roadmap

- verify low-speed chassis and small-angle gimbal motion with the wheels lifted;
- implement a gamepad-control daemon with dead-man control, a 250 ms watchdog, and speed limits;
- combine App/Lab video and official-SDK control behind a capability adapter;
- expose ROS 2 `cmd_vel`, camera, and verified telemetry safely;
- build a reproducible verification matrix for different S1 firmware versions.

## License

Hanppie's host-side code uses the [MIT License](./LICENSE). Recovered device runtime, the optional DJI SDK, and the community backend remain subject to their respective provenance and terms; see [`NOTICE.md`](./NOTICE.md).
