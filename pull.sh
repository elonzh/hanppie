#!/bin/bash

basedir="./robomaster/$(adb shell getprop ro.build.product)/$(adb shell getprop ro.build.date.utc)"
echo "basedir: " ${basedir}
mkdir -p ${basedir}

echo "get netstat"
adb shell netstat -p  > "${basedir}/netstat.txt"

echo "get processes"
adb shell ps  > "${basedir}/ps.txt"

dirs=("/system" "/init.environ.rc" "/init.lc1860.3connective.rc" "/init.rc" "/init.trace.rc" "/init.usb.rc" "/ueventd.rc" "/data")
for path in ${dirs[*]}
do
  echo "start pulling" "${path}"
  adb pull "${path}" "${basedir}${path}"
done

