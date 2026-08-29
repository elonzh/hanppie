# Contributing

Hanppie interacts with a discontinued physical robot and includes recovery
operations that can expose a root shell. Contributions must keep safe defaults,
explicit targets, checksum verification, and reversible recovery paths.

## Development setup

Install [uv](https://docs.astral.sh/uv/) and
[Task](https://taskfile.dev/), then run:

```bash
task sync
task hooks
task check
```

Use `task sync:official` or `task sync:lab` only when testing a robot backend.
The two extras are deliberately mutually exclusive because both dependencies
install a top-level Python package named `robomaster`.

The core package and official backend support Python 3.8+. The Lab backend
requires Python 3.10+ because that is the minimum declared by its upstream
package. On macOS and on Python 3.9+, set `HANPPIE_OFFICIAL_SDK_PATH` to the
pinned DJI SDK checkout documented in the README.

## Pull requests

- Keep physical actions disabled in automated tests.
- Mock ADB and network transports in unit tests.
- Document the exact S1 firmware when reporting device behavior.
- Separate “API accepted”, “telemetry observed”, and “physical effect verified”.
- Never commit device backups, serial-specific logs, vendor binaries, or keys.

Run `task prek` before opening a pull request. Hardware test evidence should be
included in the pull request description, but hardware access is not required
for ordinary contributions.
