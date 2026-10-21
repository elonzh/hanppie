# FFmpeg

Hanppie redistributes an unmodified FFmpeg executable as a separate program for
desktop media decoding, recording and audio conversion. Hanppie invokes it through
command-line pipes and does not link to the JAVE Java library.

- Copyright (c) 2000-2026 the FFmpeg developers.
- Binary distribution: JAVE2 native binaries, version 4.2.0.
  https://github.com/a-schild/jave2/tree/4.2.0
- Maven artifacts: `ws.schild:jave-nativebin-{osxm1,osx64,win64,win-arm64,linux64,linux-arm64}:4.2.0`.
  Only the build host's artifact is included in each Hanppie distribution.
- FFmpeg source: https://ffmpeg.org/releases/ and https://github.com/FFmpeg/FFmpeg
- Native binary build scripts and provenance:
  https://github.com/a-schild/jave2/tree/4.2.0/.github

These builds enable GPL components, including libx264. The executable's `-version`
and `-buildconf` commands report its exact version and configuration; `-L` reports
its license. The macOS ARM64 build identifies as FFmpeg 9.0; other platform builds
may carry a different patch version within FFmpeg 9.0.x.

FFmpeg is free software, distributed without warranty. See the accompanying
`COPYING.GPLv2` and `COPYING.GPLv3` for the GNU General Public License. JAVE2's native
binary distribution is licensed under GPL-3.0. Hanppie's own code remains under
Apache-2.0; third-party software retains its own license.
