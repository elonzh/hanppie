<img src="./assets/brand/graphite-orange/source/app-icon-master.png" width="72" align="right" alt="Hanppie icon" />

# Hanppie

[![简体中文](https://img.shields.io/badge/%E7%AE%80%E4%BD%93%E4%B8%AD%E6%96%87-DF6B38?style=flat&logo=googletranslate&logoColor=white)](README.md)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache--2.0-DF6B38?style=flat)](LICENSE)
[![macOS, Windows, Android, iOS](https://img.shields.io/badge/Platforms-macOS%20%C2%B7%20Windows%20%C2%B7%20Android%20%C2%B7%20iOS-DF6B38?style=flat)](#installation)
[![Website](https://img.shields.io/badge/Website-Hanppie-DF6B38?style=flat)](https://elonzh.cn/en/toys/hanppie)
[![Download Hanppie](https://img.shields.io/badge/Download-Hanppie-DF6B38?style=flat)](https://github.com/elonzh/hanppie/releases/latest)
[![Changelog](https://img.shields.io/badge/Changelog-DF6B38?style=flat)](CHANGELOG.md)

An open-source RoboMaster workbench. Drive your robot and write Python scripts on desktop, phone or tablet, with AI assistance when you need it.

![Hanppie](assets/brand/graphite-orange/github/social-preview.png)

## Installation

Download the package for your platform from [GitHub Releases](https://github.com/elonzh/hanppie/releases/latest). Use the accompanying `SHA256SUMS.txt` to verify it.

| Platform | Installation |
| --- | --- |
| macOS | Open the DMG and drag Hanppie into Applications; the release package targets Apple Silicon (arm64) |
| Windows | Run the x64 MSI installer |
| Android | Install the APK; Android 8.0 or later is required |
| iPhone / iPad | iOS 18.5 or later; source and an unsigned archive are available. Sign and install with Xcode, following the [development guide](docs/guides/development.md#ios) (Chinese) |

## Connect a robot

1. Open Hanppie and choose “Add or change robot”.
2. Choose direct or router mode. For direct mode, join the robot's hotspot. For router mode, enter Wi-Fi details in Hanppie and let the robot scan the generated QR code.
3. Allow local network access when prompted. Once connected, you can start using the workbench. On subsequent launches, the app attempts to restore the previous connection.

The official RoboMaster app is not required. Manual IPv4/AppID entry is available under Diagnostics for troubleshooting.

## Usage

| Task | How |
| --- | --- |
| App language | In Settings → General → Language, choose 简体中文, English or System default. Changes apply immediately and are saved; robot voice language is configured separately |
| Drive | Enter the cockpit after connecting. Use the twin sticks, or WASD to move, Q/E to turn and arrow keys for the gimbal. Esc returns to the workbench. See Settings → Shortcuts for all controls |
| Control settings | In Settings → Controls, tune keyboard/screen-stick response and app speed limits separately. Open Robot motion parameters to read device settings after connecting, explicitly write changes and check readback. Hardware behavior is [pending validation](docs/records/official-app/implementation.md#i01-输入响应与运动参数实施进度及设备待验) |
| Gyroscope aiming | On a device with a gyroscope, select Gyroscope in the cockpit and rotate the device to aim. Tap again or press Esc to stop. Set sensitivity and vertical direction in Settings → Controls; restart explicitly after leaving or an interruption. See [pending native sensor and hardware checks](docs/architecture/hanppie.md#134-遥控操纵镜头坐标系与安全联动) (Chinese) |
| Object tracking | In the cockpit, choose Object tracking, tap a target, then select Start following after it locks. Stop tracking or use a direction control to take over. See the [hardware validation boundary](docs/architecture/hanppie.md#134-遥控操纵镜头坐标系与安全联动) (Chinese) |
| Camera zoom | In the cockpit, select the zoom control to switch between 1× and 4×. The camera confirms its ratio; return to 1× before starting tracking. See the [pending hardware checks](docs/records/official-app/implementation.md#a02-验证入口与设备待验) (Chinese) |
| Camera settings | In Settings → Media → Onboard camera, set anti-flicker, read the camera clock, or sync this device’s local time. An unsynchronized status triggers one automatic sync attempt per connection. See [pending hardware checks](docs/records/official-app/implementation.md#a03-验证入口与设备待验) (Chinese) |
| External lighting | In Settings → Robot maintenance → External lighting, read brightness for PWM lighting accessories. Adjustment is available after a successful read. Use Status lights for the armour LEDs; see the [hardware boundary](docs/architecture/hanppie.md#134-遥控操纵镜头坐标系与安全联动) (Chinese) |
| Image and capture | In Settings → Media → Onboard camera → Image and capture, view the reported format, choose photo dimensions or transmission rate, or request a video refresh. Select a source when the robot reports multiple cameras. See [pending hardware checks](docs/records/official-app/implementation.md#a07-验证入口与设备待验) (Chinese) |
| Storage card maintenance | In Settings → Robot maintenance → Onboard SD card, view onboard SD card status and capacity, then confirm file deletion to request formatting. See [pending hardware checks](docs/records/official-app/implementation.md#a04-验证入口与设备待验) (Chinese) |
| Robot Wi-Fi | In Settings → Robot maintenance → Robot Wi-Fi, query network information. In AP mode, edit the name and password, then separately confirm a Wi-Fi restart. Join the network in system settings and return to reconnect and verify the configuration. See [pending hardware checks](docs/records/official-app/implementation.md#n01-验证入口与设备待验) (Chinese) |
| Channels and interference | Open this tab on the Robot Wi-Fi page to query the current channel and the device's available list. In AP mode, confirm automatic/manual selection or a manual channel. If the result is unconfirmed, check system Wi-Fi and reconnect on the same page to verify it. See [pending hardware checks](docs/records/official-app/implementation.md#n02-验证入口与设备待验) (Chinese) |
| SDR configuration | SDR controls appear in Settings → Robot maintenance after the current connection reports SDR mode and valid configuration. Query settings, confirm automatic/manual selection, and adjust band, bandwidth and frequency in manual mode as supported. Reconnect there to verify an unconfirmed change. See [pending hardware checks](docs/records/official-app/implementation.md#n03-验证入口与设备待验) (Chinese) |
| Robot status | Open the alert count on Home or in the cockpit, or Settings → Robot maintenance → Robot status, for battery, warnings, model, activation and main firmware. Expand Component status and versions to see each component together. Distance and driving time appear when reported. See [capabilities and validation](./docs/architecture/hanppie.md#110-当前能力矩阵) (Chinese). |
| Power and programming sleep | In Settings → Robot maintenance → Power and standby, automatically read programming sleep settings and confirm a timeout or disable them. Separately confirm standby, wake, restart or shutdown. Actions other than wake end the session; check the robot before reconnecting. Opening the editor does not switch robot modes. See [C14 and pending hardware checks](docs/records/official-app/implementation.md#h03-验证入口与设备待验) (Chinese) |
| Onboard SD capture | Select SD capture at the top left of the cockpit to take photos or start/stop onboard recording. The entry shows onboard recording time; existing local capture controls remain separate. See [pending hardware checks](docs/records/official-app/implementation.md#a05-验证入口与设备待验) (Chinese) |
| Media library | Open Media library from the app navigation. Files show their names and creation times. Local media works offline: preview photos, play recordings with audio in the app, export or delete files. Desktop captures appear automatically. Onboard SD card shows photo and video previews with camera creation times; download recordings to play them. See [pending hardware checks](docs/records/official-app/implementation.md#a06-验证入口与设备待验) (Chinese) |
| Onboard audio | In Settings → Media → Onboard audio, read the robot volume, apply a volume and verify it, or change the robot voice language independently of the app language. See the [pending hardware checks](docs/records/official-app/implementation.md#a01-验证入口与设备待验) (Chinese) |
| Microphone and playback | In Settings → Media → Onboard audio → Robot microphone and playback, query the microphone setting, view recent playback status or request feedback suppression. Hold the cockpit talk control to record a short clip; releasing uploads it for playback. See [pending hardware checks](docs/records/official-app/implementation.md#a08-验证入口与设备待验) (Chinese) |
| Write scripts | Create a script, import a `.py` file or copy a preset in Scripts. Save it locally, then choose Run script. Lab programs use onboard APIs and Python 3.6 syntax; see the [Lab programming guide](shared/src/commonMain/composeResources/files/skills/lab-python/SKILL.md) (Chinese) |
| Program panel | When an onboard program creates visible controls, open Program panel in Scripts to use its buttons, toggles, sliders, options or inputs. Scroll the layout, open the monitor or return to editing. Closing the panel does not stop the program; use its Stop control. See [pending hardware checks](docs/records/official-app/implementation.md#p03-程序面板与设备待验) (Chinese) |
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

Use `from hanppie import Robot` for native direct control, or the bundled `robomaster` SDK fork. See the [development guide](docs/guides/development.md) (Chinese) for source builds, tests and a Python example.

## Reference

- [Documentation guide](./docs/README.md) (Chinese): where to start, development, architecture, product rules, official App references and validation records.
- [Design guidelines](./DESIGN.md) (Chinese): product experience, layout and visual language.

Hardware validation is recorded in the architecture's [capability matrix](docs/architecture/hanppie.md#110-当前能力矩阵). Hanppie is an independent project and is not affiliated with DJI.

Hanppie's own code uses [Apache License 2.0](LICENSE). See the [FFmpeg notice](third-party/ffmpeg/NOTICE.md) for the bundled executable's origin and license, and [UPSTREAM.md](src/robomaster/UPSTREAM.md) for the DJI-derived SDK code.
