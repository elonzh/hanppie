# Upstream provenance

This package is a source-compatible RoboMaster Python SDK fork maintained as
part of Hanppie for DJI RoboMaster S1 interoperability.

- Upstream: <https://github.com/dji-sdk/RoboMaster-SDK>
- Imported commit: `ff6646e115ab125af3207a4ed3df42cc76c795b2`
- Upstream version: `0.1.1.68`
- Imported scope: `src/robomaster/*.py`
- Excluded: prebuilt media libraries, installers, archives, examples, and
  documentation unrelated to the runtime package
- License: Apache License 2.0; see `LICENSE.txt`

The initial import normalizes line endings. Files changed after the import must
retain DJI's copyright and license header and carry a clear modification note.
The public import path remains `robomaster` to preserve compatibility with DJI
examples and downstream integrations. S1 compatibility is established per API
through Hanppie's physical-device capability matrix, not implied by the package
name.
