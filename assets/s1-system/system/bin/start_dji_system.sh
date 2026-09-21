#!/system/bin/sh

insmod /system/lib/modules/lc186x-snd-soc.ko

# Check partitions, try format it and reboot when failed
mkdir -p /data/ftp/blackbox
/system/bin/part_check.sh
# hack for blackbox
mkdir -p /data/ftp/blackbox/vision
mkdir -p /data/ftp/flyctrl
mkdir -p /data/ftp/v2
mkdir -p /blackbox/vision
mkdir -p /blackbox/flyctrl
mkdir -p /data/vision
busybox mount --bind /blackbox/vision /data/ftp/blackbox/vision

#set emmc irq cpu affinity, dw_mci
echo 02 > /proc/irq/66/smp_affinity
echo 02 > /proc/irq/67/smp_affinity

#set ap_dma irq cpu affinity, ap_dma
echo 08 > /proc/irq/34/smp_affinity

# need to judge sdr or wifi
/system/bin/wl_link_judge.sh
wl_link_type=$?
if [ $wl_link_type -ge 1 ]; then
	setprop wl.link.prefer SDR
	echo "set wl_link_type = SDR" > /dev/ttyS1
else
	setprop wl.link.prefer WIFI
	echo "set wl_link_type = WIFI" > /dev/ttyS1
fi

#check cp_assert log size, if more than 32KB remove it
#do it before sdrs start, so new log would not be lost
if [ -f /data/dji/log/cp_assert.log ]; then
	cp_assert_file_size=`busybox wc -c /data/dji/log/cp_assert.log | busybox awk '{printf $1}'`
	if [ $cp_assert_file_size -gt 32768 ]; then
		rm -rf /data/dji/log/cp_assert.log
	fi
fi

if [ $wl_link_type -ge 1 ]; then
	setprop dji.sdrs 1
fi

debug=false
grep production /proc/cmdline >> /dev/null
if [ $? != 0 ];then
	debug=true	# engineering version, enable adb by default
else
	cmdline=`cat /proc/cmdline`
	temp=${cmdline##*board_sn=}
	board=${temp%% *}
	in_whitelist.sh $board
	if [ $? == 0 ]; then
		debug=true
	#else
		#disable uart
		# busybox devmem 0xe10093d0 8 0x41
		# busybox devmem 0xe10093d4 8 0x43
	fi
fi

if $debug; then
	/system/bin/adb_en.sh
else
	setprop sys.usb.config rndis,mass_storage,bulk,acm
fi

if [ $wl_link_type -ge 1 ]; then
	setprop dji.sdrs_log 1
fi

# set ip address one more time to avoid possible lost
ifconfig usb0 192.168.1.10

# rndis
ifconfig rndis0 192.168.42.2

mkdir /var/lib
mkdir /var/lib/misc
echo > /var/lib/misc/udhcpd.lease
busybox udhcpd
# ftp server on all the interface
busybox tcpsvd -vE 0 21 busybox ftpd -w /ftp &

# dump system/upgrade log to a special file
#logcat | grep DUSS\&5a >> /data/dji/log/upgrade.log &
mkdir -p /data/upgrade/backup
mkdir -p /data/upgrade/unsignimgs
mkdir -p /data/upgrade/incomptb
mkdir -p /data/ftp/upgrade/upgrade
mkdir -p /data/ftp/upgrade/upgrade/signimgs

#config for sound speaker
tinymix 0 1
tinymix 1 0
tinymix 2 1 0
tinymix 5 20
tinymix 6 25 25
tinymix 11 103 103
tinymix 40 1 #add by martin.wei 2017/10/15
tinymix 45 0 #add by martin.wei 2017/10/15
#config for sound mic
tinymix 14 35
tinymix 15 35
tinymix 21 1
tinymix 25 1
tinymix 26 1
tinymix 50 ADC1 # changed by martin.wei 2017.10.26
tinymix 51 ADC2 # changed by martin.wei 2017.10.26
tinymix 54 MIC1
tinymix 55 MIC1PGA
tinymix 56 MIC2

# clean up dump files
rm -Rf /data/dji/dump/5
busybox mv /data/dji/dump/4 /data/dji/dump/5
busybox mv /data/dji/dump/3 /data/dji/dump/4
busybox mv /data/dji/dump/2 /data/dji/dump/3
busybox mv /data/dji/dump/1 /data/dji/dump/2
busybox mv /data/dji/dump/0 /data/dji/dump/1
mkdir /data/dji/dump/0
busybox find /data/dji/dump/ -maxdepth 1 -type f | busybox xargs -I '{}' mv {} /data/dji/dump/0/

# CP SDR channel
if [ $wl_link_type -ge 1 ]; then
	dji_net.sh uav &
fi

if [ -d /sdcard/DCIM/Record ];
	then
		echo "====================backup recording files================" > /dev/ttyS1
		if [ -d /sdcard/DCIM/Record0 ];
			then
				echo "=========== mv /sdcard/DCIM/Record/* /sdcard/DCIM/Record0/ ================" > /dev/ttyS1
				mv /sdcard/DCIM/Record/* /sdcard/DCIM/Record0/
				mv /sdcard/DCIM/Record/.* /sdcard/DCIM/Record0/
			else
				echo "=========== mv /sdcard/DCIM/Record /sdcard/DCIM/Record0 =============" > /dev/ttyS1
				mv /sdcard/DCIM/Record /sdcard/DCIM/Record0
				mkdir -p /sdcard/DCIM/Record
		fi
	else
		echo "=========== mkdir -p /sdcard/DCIM/Record =============" > /dev/ttyS1
		mkdir -p /sdcard/DCIM/Record
fi

setprop dji.hdvt_cls 0
setprop dji.hdvt_service 1
setprop dji.camera_service 1
#setprop dji.perception_service 1

# Start services
export HOME=/data

if [ $wl_link_type -ge 1 ]; then
	setprop dji.network_service 0
	setprop dji.sw_uav_service  0
else
	setprop dji.network_service 1
	setprop dji.sw_uav_service  1
fi

setprop dji.monitor_service 1
setprop dji.system_service 1
setprop dji.blackbox_service 1

###Here we change it to 15s to avoid ssd probe fail issue##
if [ -f /data/dji/cfg/ssd_en ]; then # Disabled by default
	i=0
	while [ $i -lt 25 ]; do
		if [ -b /dev/block/sda1 ]
		then
			mkdir -p /data/image
			mount -t ext4 /dev/block/sda1 /data/image
			break
		fi
		i=`busybox expr $i + 1`
		sleep 1
	done
fi
setprop dji.vision_service 1

# For debug
debuggerd&
mkdir -p /data/dji/log
mkdir -p /data/dji/cfg/test
# Auto save logcat to flash to help trace issues
if [ -f /data/dji/cfg/field_trail ]; then
	# Enable bionic libc memory leak/corruption detection
	setprop libc.debug.malloc 10
	# Up to 5 files, each file upto 32MB
	logcat -f /data/dji/log/logcat.log -r32768 -n4 *:I &
fi
# Capture temperature
#test_thermal.sh >> /data/dji/log/temperature.log &

if [ -f /data/dji/amt/state ]; then
	amt_state=`cat /data/dji/amt/state`
fi

# dump system/upgrade log to a special file
rm /data/dji/upgrade_log.tar.gz
upgrade_file_size=`busybox wc -c < /data/dji/log/upgrade00.log`
if [ $upgrade_file_size -gt 2097152 ]; then
mv /data/dji/log/upgrade07.log /data/dji/log/upgrade08.log
mv /data/dji/log/upgrade06.log /data/dji/log/upgrade07.log
mv /data/dji/log/upgrade05.log /data/dji/log/upgrade06.log
mv /data/dji/log/upgrade04.log /data/dji/log/upgrade05.log
mv /data/dji/log/upgrade03.log /data/dji/log/upgrade04.log
mv /data/dji/log/upgrade02.log /data/dji/log/upgrade03.log
mv /data/dji/log/upgrade01.log /data/dji/log/upgrade02.log
mv /data/dji/log/upgrade00.log /data/dji/log/upgrade01.log
else
echo -e "\n\n!!!new file start!!!\n">> /data/dji/log/upgrade00.log
fi
logcat -v threadtime |stdbuf -oL grep DUSS\&63 >> /data/dji/log/upgrade00.log &

env_amt_state=`env amt.state`
if [ "$env_amt_state"x == "factory_out"x ]; then
	env -d amt.state
	echo factory > /data/dji/amt/state
fi

if [ -f /data/dji/amt/state ]; then
	amt_state=`cat /data/dji/amt/state`
fi
env_boot_mode=`env boot.mode`

config_soc_pwm0_for_xw607.sh &

if [ "$amt_state"x == "factory"x -o \
     "$amt_state"x == "aging_test"x -o \
     "$amt_state"x == "factory_out"x -o \
     "$env_boot_mode"x == "factory_out"x ]; then

	if [ "$amt_state"x == "aging_test"x ]; then
		echo "start aging_test..." > /dev/ttyS1
		/system/bin/aging_test.sh
	fi

	if [ "$amt_state"x == "factory"x ]; then
		# Need to enable bootarea1 write for enc
		echo 0 > /sys/block/mmcblk0boot1/force_ro
	fi

	exit 0
fi

if [ -f /sdcard/factory_out/aging_test_car ]; then
    echo "Start aging_test..." > /dev/ttyS1
    /system/bin/aging_test.sh
    exit 0
fi

if [ -f /sdcard/factory_out/aging_test_board ]; then
    echo "Start board aging test" > /dev/ttyS1
    /system/bin/aging_test_board.sh
    exit 0
fi

# WIFI
# Check if usb wifi card is inserted
#RETRY_COUNT=1
#while [ $RETRY_COUNT -ge 0 ]
#do
#    busybox lsusb | grep 1022
#    if [ $? = 0 ]
#    then
#       setprop dji.network_service 1
#       break
#   else
#       echo "No wifi usb device" >> /data/dji/log/start_dji_system.log
#       busybox lsusb >> /data/dji/log/start_dji_system.log
#       sleep 1
#    fi
#    let RETRY_COUNT-=1
#done
# Check whether do auto sdr test
if [ -f /data/dji/cfg/amt_sdr_test.cfg ]; then
	/system/bin/test_sdr.sh
fi

# Here we update recovery.img since all the service should be started.
# We could make the recovery.img work before this script exit for some
# service not startup.
/system/bin/recovery_update.sh

env_boot_mode=`env boot.mode`
#no need do next steps in factory mode
if [ "$amt_state"x == "factory"x -o "$amt_state"x == "aging_test"x -o "$env_boot_mode"x == "factory_out"x ]; then
	env wipe_counter 0
	env crash_counter 0
	if [ "$amt_state"x == "aging_test"x ]; then
		echo "start aging_test..." > /dev/ttyS1
		/system/bin/aging_test.sh
	fi
	exit 0
fi

# for fatal errors, up to 32MB
logcat -v time -f /data/dji/log/fatal.log -r32768 -n4 *:F &

ps | grep dji_sys
if [ $? != 0 ];then
	echo "crash_counter: dji_sys not exist" > /data/dji/log/crash_counter.log
	sync
	exit -1
fi

ps | grep dji_hdvt_uav
if [ $? != 0 ];then
	echo "crash_counter: dji_hdvt_uav not exist" > /data/dji/log/crash_counter.log
	sync
	exit -1
fi


ps | grep dji_monitor
if [ $? != 0 ];then
	echo "crash_counter: dji_monitor not exist" > /data/dji/log/crash_counter.log
	sync
	exit -1
fi

env wipe_counter 0
env crash_counter 0

# dump LC1860 state
check_1860_state.sh &

# panic and tombstones check
panic_tombstone_check.sh &

# dump wifi log
# wifi log will be output only when usb inserted
# and there is a wifi.debug file in usb root dir
# /system/bin/wifi_debug.sh &
# dump profiled wifi log
/system/bin/wifi_profiled_debug.sh &
# monitor, poweroff
#/system/bin/power_off_for_runner.sh &

# Check whether do auto OTA upgrade test
if [ -f /data/dji/cfg/test/ota ]; then
	/system/bin/test_ota.sh
fi

# Check whether do auto reboot test
if [ -f /data/dji/cfg/test/reboot ]; then
	sleep 20
	reboot
fi

setprop dji.scratch_service 1
