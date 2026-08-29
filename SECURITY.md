# Security policy

## Reporting a vulnerability

Please report security issues privately through GitHub Security Advisories for
this repository. Do not include device serial numbers, Wi-Fi credentials,
private IP topology, or unredacted robot backups in a public issue.

## Device security warning

The ADB bootstrap can expose an unauthenticated root shell on TCP port 5555.
Use it only on a trusted, isolated network and reboot the S1 when maintenance is
complete. Never configure router port forwarding for the robot.

The official-SDK compatibility patch is intentionally volatile: it uses bind
mounts and is removed by a reboot. Persistent modification of the S1 boot chain
is outside the supported workflow.
