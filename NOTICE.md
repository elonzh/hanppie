# Notices and provenance

Hanppie is an independent preservation project and is not affiliated with or
endorsed by DJI.

The modules under `src/hanppie/runtime` and the configuration under
`src/hanppie/resources` were recovered from a user's RoboMaster S1 for
interoperability research. Their presence documents the protocol boundary; it
does not grant rights to DJI trademarks or third-party firmware. The host-side
Hanppie code and project tooling are released under the repository's MIT
license.

The `src/robomaster` package is derived from DJI's RoboMaster Python SDK
version 0.1.1.68 at commit `ff6646e115ab125af3207a4ed3df42cc76c795b2`.
Those files retain DJI's copyright headers and are distributed under the
Apache License 2.0 included at `src/robomaster/LICENSE.txt`; Hanppie's MIT
license does not replace that license. Import details and local modifications
are recorded in `src/robomaster/UPSTREAM.md`.

The isolated `lab` development environment fetches the separately maintained
RoboMaster-S1-WiFi-SDK project at a fixed commit. Hanppie does not redistribute
that source; review its upstream terms before redistributing it.
