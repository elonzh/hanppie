<img src="./assets/brand/graphite-orange/source/app-icon-master.png" width="72" align="right" alt="Hanppie icon" />

# Hanppie

[简体中文](./README.md) | [English](./README_EN.md)

An open-source RoboMaster workbench. Drive your robot and write Python scripts on desktop, phone or tablet, with AI assistance when you need it.

[Website](https://elonzh.cn/en/toys/hanppie) · [Download](https://github.com/elonzh/hanppie/releases/latest) · [Changelog](./CHANGELOG.md)

![Hanppie](./assets/brand/graphite-orange/github/social-preview.png)

## Installation

Download the package for your platform from [GitHub Releases](https://github.com/elonzh/hanppie/releases/latest). Use the accompanying `SHA256SUMS.txt` to verify it.

| Platform | Installation |
| --- | --- |
| macOS | Open the DMG and drag Hanppie into Applications; the release package targets Apple Silicon (arm64) |
| Windows | Run the x64 MSI installer |
| Android | Install the APK; Android 8.0 or later is required |
| iPhone / iPad | iOS 18.5 or later; source and an unsigned archive are available. Sign and install with Xcode, following the [development guide](./docs/development.md#ios) (Chinese) |

Desktop audio and video require [FFmpeg](https://ffmpeg.org/download.html) with H.264 and Opus support. Add `ffmpeg` to the application process PATH or set its executable path with `HANPPIE_FFMPEG`. Android and iOS use native media support. Use the app in landscape orientation.

## Connect a robot

1. Open Hanppie and choose “Add or change robot”.
2. Choose direct or router mode. For direct mode, join the robot's hotspot. For router mode, enter Wi-Fi details in Hanppie and let the robot scan the generated QR code.
3. Allow local network access when prompted. Once connected, you can start using the workbench. On subsequent launches, the app attempts to restore the previous connection.

The official RoboMaster app is not required. Manual IPv4/AppID entry is available under Diagnostics for troubleshooting.

## Usage

| Task | How |
| --- | --- |
| Drive | Enter the cockpit after connecting. Use the twin sticks, or WASD to move, Q/E to turn and arrow keys for the gimbal. Esc returns to the workbench. See Settings → Shortcuts for all controls |
| Write scripts | Create a script, import a `.py` file or copy a preset in Scripts. Save it locally, then choose Run script. Lab programs use onboard APIs and Python 3.6 syntax; see the [Lab programming guide](./shared/src/commonMain/composeResources/files/skills/lab-python/SKILL.md) (Chinese) |
| Use AI assistance | Choose a provider in Settings → Model service, enter the API key and model, then test the configuration. Describe your task in Chat and review the complete script before approving execution |
| Stop a script | Use Stop script. Leaving the editor or cancelling AI generation does not stop an onboard script |

Running a script overwrites the robot's current Lab execution slot. Review the source and leave clear space before starting motion. Text conversations are sent to your chosen model provider; API keys are stored in the app's private data.

## Python and development

The Python tools use Python 3.10 and [uv](https://docs.astral.sh/uv/):

```bash
git clone https://github.com/elonzh/hanppie.git
cd hanppie
uv sync
```

Use `from hanppie import Robot` for native direct control, or the bundled `robomaster` SDK fork. See the [development guide](./docs/development.md) (Chinese) for source builds, tests and a Python example.

## Reference

- [Technical architecture](./docs/architecture.md) (Chinese): implementation, device capabilities and safety boundaries.
- [RoboMaster architecture and protocols](./docs/architecture-robomaster.md) (Chinese): onboard mechanisms, protocols and upstream research.
- [Design guidelines](./DESIGN.md) (Chinese): product experience, layout and visual language.

Hardware validation is recorded in the architecture's [capability matrix](./docs/architecture.md#110-当前能力矩阵). Hanppie is an independent project and is not affiliated with DJI.

Hanppie's own code uses [Apache License 2.0](./LICENSE). See [UPSTREAM.md](./src/robomaster/UPSTREAM.md) for the origin and license of the DJI-derived SDK code.
