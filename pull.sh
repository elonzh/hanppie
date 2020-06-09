#!/bin/bash

basedir="$(pwd)/robomaster/$(adb shell getprop ro.product.name | tr -d '\r')/$(adb shell getprop ro.build.date.utc | tr -d '\r')"
echo "basedir: ${basedir}"
mkdir -p "${basedir}"

echo "get netstat"
adb shell netstat -p  > "${basedir}/netstat.txt"

echo "get processes"
adb shell ps  > "${basedir}/ps.txt"

#dirs=("/system" "/init.environ.rc" "/init.lc1860.3connective.rc" "/init.rc" "/init.trace.rc" "/init.usb.rc" "/ueventd.rc" "/data")
dirs=("/system/firm/dji_scratch" "/system/etc/dji.json" "/system/bin" "/system/build.prop" "/init.environ.rc" "/init.lc1860.3connective.rc" "/init.rc" "/init.trace.rc" "/init.usb.rc" "/ueventd.rc")
for path in ${dirs[*]}
do
  echo "start pulling" "${path}"
  adb pull "${path}" "${basedir}${path}"
done

link="$(pwd)/robomaster/current"
rm -vf "${link}"
ln -sf "$(realpath "${basedir}")" "${link}"

# TODO 修改代码中默认的读取路径
sudo mkdir -p /system/etc/
sudo ln -s "$(pwd)/robomaster/current/system/etc/dji.json" /system/etc/dji.json

echo "add these paths to your PYTHONPATH:"
echo "$(pwd)/robomaster/current/system/firm/dji_scratch/lib"
echo "$(pwd)/robomaster/current/system/firm/dji_scratch/src/robomaster"
echo "$(pwd)/robomaster/current/system/firm/dji_scratch/src/robomaster/custom_ui"
echo "$(pwd)/robomaster/current/system/firm/dji_scratch/src/robomaster/multi_comm"
