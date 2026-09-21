#!/system/bin/sh

level=$1
: ${level:=NonSecurePrivilege}

# mark debug enable
mkdir -p /tmp/dji
echo $level > /tmp/dji/secure_debug

# init adb device serial
if [ -f /data/dji/cfg/adb_serial ]; then
serial=`cat /data/dji/cfg/adb_serial`
busybox printf "$serial" > /sys/class/android_usb/android0/iSerial
fi

setprop service.adb.root 1
setprop service.adb.tcp.port -1
#setprop service.adb.tcp.port 5555
setprop sys.usb.config rndis,mass_storage,bulk,acm,adb
setprop secure.debug true
busybox devmem 0xe10093d0 8 0x40	#enable uart
busybox devmem 0xe10093d4 8 0x48
sleep 1
busybox udhcpd
