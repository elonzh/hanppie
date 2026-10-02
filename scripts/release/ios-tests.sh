#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p build/ios-tests
# Keep the installed runtime; downloading one here would hide missing runner setup.
runtime=$(xcrun simctl list runtimes -j | python3 -c 'import json,sys; r=[x for x in json.load(sys.stdin)["runtimes"] if x.get("isAvailable") and x["identifier"].startswith("com.apple.CoreSimulator.SimRuntime.iOS-") and tuple(map(int,x["version"].split("."))) >= (18,5)]; assert r,"An iOS 18.5+ simulator runtime is required"; print(max(r,key=lambda x:tuple(map(int,x["version"].split("."))))["identifier"])')
core_tested=false
for device in 'iPhone 16' 'iPad Pro 11-inch (M4)'; do
  type=$(xcrun simctl list devicetypes -j | DEVICE="$device" python3 -c 'import json,os,sys; print(next(x["identifier"] for x in json.load(sys.stdin)["devicetypes"] if x["name"]==os.environ["DEVICE"]))')
  udid=$(xcrun simctl create "Hanppie $device" "$type" "$runtime")
  trap 'xcrun simctl delete "$udid" >/dev/null 2>&1 || true' EXIT
  xcrun simctl boot "$udid"
  xcrun simctl bootstatus "$udid" -b
  if [[ "$core_tested" == false ]]; then
    ./gradlew :packages:robot-core:iosSimulatorArm64Test --device "$udid"
    core_tested=true
  fi
  slug=$(echo "$device" | tr ' ()' '---')
  # xcresult retains logs, UI recordings and screenshots; skip device-wide sysdiagnoses.
  xcodebuild -project iosApp/Hanppie.xcodeproj -scheme Hanppie -configuration Debug \
    -destination "platform=iOS Simulator,id=$udid" -derivedDataPath build/ios-derived \
    -resultBundlePath "build/ios-tests/$slug.xcresult" \
    -parallel-testing-enabled NO -collect-test-diagnostics never CODE_SIGNING_ALLOWED=NO test
  xcrun simctl shutdown "$udid"
  xcrun simctl delete "$udid"
  trap - EXIT
done
