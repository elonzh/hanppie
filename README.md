## hanppie(憨皮)

RoboMaster S1　不提供官方的 SDK，尝试着通过代理的方式在机器上执行命令

## 一些信息

- 上传的代码位置在 `/data/script/file/` 
- 录音文件在 `/data/audio_files`, 格式为 wav, 其中有一个 `rm_audio_setting.json` 文件, `interconn_audio_id` 为上传的音频文件时间戳
- 内部设备通信使用 socket 文件, 且为 UDP 协议 `socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)`
- 整个脚本执行由 `/data/python_files/bin/python /data/python_files/bin/python/data/dji_scratch/bin/dji_scratch.py` 控制 
- 目前最新版固件　`00.06.0100`　RoboMaster S1 没有集成 sdk_manager 模块  

## RoboMaster S1 机器信息

```shell script
# getprop
[ro.allow.mock.location]: [0]
[ro.baseband]: [unknown]
[ro.board.platform]: [lc1860]
[ro.bootloader]: [unknown]
[ro.bootmode]: [unknown]
[ro.build.characteristics]: [default]
[ro.build.date.utc]: [1573465747]
[ro.build.date]: [Mon Nov 11 17:49:07 CST 2019]
[ro.build.description]: [full_xw607_dz_ap0002_v4-userdebug 4.4.4 KTU84Q eng.jenkins.20191111.174757 test-keys]
[ro.build.display.id]: [leadcore1860]
[ro.build.host]: [APServer01]
[ro.build.id]: [KTU84Q]
[ro.build.product]: [xw607_dz_ap0002_v4]
[ro.build.tags]: [test-keys]
[ro.build.type]: [userdebug]
[ro.build.user]: [jenkins]
[ro.build.version.codename]: [REL]
[ro.build.version.incremental]: [eng.jenkins.20191111.174757]
[ro.build.version.release]: [4.4.4]
[ro.build.version.sdk]: [19]
[ro.debuggable]: [1]
[ro.factorytest]: [0]
[ro.hardware]: [leadcoreinnopower]
[ro.product.board]: [evb2]
[ro.product.brand]: [Leadcore]
[ro.product.cpu.abi2]: [armeabi]
[ro.product.cpu.abi]: [armeabi-v7a]
[ro.product.device]: [xw607_dz_ap0002_v4]
[ro.product.hardware.version]: [Ver0606]
[ro.product.locale.language]: [en]
[ro.product.locale.region]: [US]
[ro.product.manufacturer]: [LEADCORE]
[ro.product.model]: [L1860]
[ro.product.name]: [full_xw607_dz_ap0002_v4]
[sys.usb.config]: [rndis,mass_storage,bulk,acm,adb]
[sys.usb.state]: [rndis,mass_storage,bulk,acm,adb]
```

```shell script
# cat /proc/cpuinfo                                  
processor	: 0
model name	: ARMv7 Processor rev 5 (v7l)
Processor	: ARMv7 Processor rev 5 (v7l)
BogoMIPS	: 26.00
Features	: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt 
CPU implementer	: 0x41
CPU architecture: 7
CPU variant	: 0x0
CPU part	: 0xc07
CPU revision	: 5

processor	: 1
model name	: ARMv7 Processor rev 5 (v7l)
Processor	: ARMv7 Processor rev 5 (v7l)
BogoMIPS	: 26.00
Features	: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt 
CPU implementer	: 0x41
CPU architecture: 7
CPU variant	: 0x0
CPU part	: 0xc07
CPU revision	: 5

processor	: 2
model name	: ARMv7 Processor rev 5 (v7l)
Processor	: ARMv7 Processor rev 5 (v7l)
BogoMIPS	: 26.00
Features	: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt 
CPU implementer	: 0x41
CPU architecture: 7
CPU variant	: 0x0
CPU part	: 0xc07
CPU revision	: 5

processor	: 3
model name	: ARMv7 Processor rev 5 (v7l)
Processor	: ARMv7 Processor rev 5 (v7l)
BogoMIPS	: 26.00
Features	: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt 
CPU implementer	: 0x41
CPU architecture: 7
CPU variant	: 0x0
CPU part	: 0xc07
CPU revision	: 5

processor	: 4
model name	: ARMv7 Processor rev 5 (v7l)
Processor	: ARMv7 Processor rev 5 (v7l)
BogoMIPS	: 26.00
Features	: swp half thumb fastmult vfp edsp neon vfpv3 tls vfpv4 idiva idivt 
CPU implementer	: 0x41
CPU architecture: 7
CPU variant	: 0x0
CPU part	: 0xc07
CPU revision	: 5

Hardware	: Leadcore Innopower
Revision	: 0000
Serial		: 0000000000000000
```

```shell script
# cat /proc/meminfo                                  
MemTotal:         271708 kB
MemFree:           57556 kB
Buffers:           13532 kB
Cached:           118496 kB
SwapCached:            0 kB
Active:            67220 kB
Inactive:         110592 kB
Active(anon):      46352 kB
Inactive(anon):      140 kB
Active(file):      20868 kB
Inactive(file):   110452 kB
Unevictable:         496 kB
Mlocked:               0 kB
HighTotal:             0 kB
HighFree:              0 kB
LowTotal:         271708 kB
LowFree:           57556 kB
SwapTotal:             0 kB
SwapFree:              0 kB
Dirty:                16 kB
Writeback:             0 kB
AnonPages:         46540 kB
Mapped:            11792 kB
Shmem:               172 kB
Slab:              12128 kB
SReclaimable:       6360 kB
SUnreclaim:         5768 kB
KernelStack:        2048 kB
PageTables:         1164 kB
NFS_Unstable:          0 kB
Bounce:                0 kB
WritebackTmp:          0 kB
CommitLimit:      135852 kB
Committed_AS:     288336 kB
VmallocTotal:     745472 kB
VmallocUsed:      153220 kB
VmallocChunk:     458172 kB
```

```shell script
root@xw607_dz_ap0002_v4:/ # netstat -p                                         
Proto Recv-Q Send-Q Local Address          Foreign Address        State
 tcp       0      0 0.0.0.0:8905           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8906           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8907           0.0.0.0:*              LISTEN
 tcp       0      0 127.0.0.1:5037         0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8909           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8910           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8912           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8913           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:8916           0.0.0.0:*              LISTEN
 tcp       0      0 0.0.0.0:21             0.0.0.0:*              LISTEN
 udp       0    704 0.0.0.0:44033          0.0.0.0:*              CLOSE
 udp       0    704 0.0.0.0:42775          0.0.0.0:*              CLOSE
 udp       0      0 0.0.0.0:67             0.0.0.0:*              CLOSE
 udp       0      0 0.0.0.0:67             0.0.0.0:*              CLOSE
 udp       0      0 0.0.0.0:10607          0.0.0.0:*              CLOSE
```

```shell script
root@xw607_dz_ap0002_v4:/ # ls /system/xbin/                                   
add-property-tag    libc_test_static    oprofiled           sqlite3           
busybox             librank             procmem             strace            
check-lost+found    memtrack            procrank            su                
cpustats            memtrack_share      rawbu               tcpdump           
ksminfo             micro_bench         sane_schedstat      
latencytop          micro_bench_static  showmap             
libc_test           opcontrol           showslab 
```

```shell script
root@xw607_dz_ap0002_v4:/ # ps                                                 
USER     PID   PPID  VSIZE  RSS     WCHAN    PC         NAME
root      1     0     508    340   c013a7b0 0001a8bc S /init
root      2     0     0      0     c0088594 00000000 S kthreadd
root      3     2     0      0     c008f0b0 00000000 S ksoftirqd/0
root      5     2     0      0     c00831a8 00000000 S kworker/0:0H
root      7     2     0      0     c008f0b0 00000000 S migration/0
root      8     2     0      0     c00cbc2c 00000000 S rcu_preempt
root      9     2     0      0     c00cb924 00000000 S rcu_bh
root      10    2     0      0     c00cb924 00000000 S rcu_sched
root      11    2     0      0     c008f0b0 00000000 S watchdog/0
root      12    2     0      0     c008f0b0 00000000 S watchdog/1
root      13    2     0      0     c008f0b0 00000000 S migration/1
root      14    2     0      0     c008f0b0 00000000 S ksoftirqd/1
root      15    2     0      0     c00831a8 00000000 S kworker/1:0
root      16    2     0      0     c00831a8 00000000 S kworker/1:0H
root      17    2     0      0     c008f0b0 00000000 S watchdog/2
root      18    2     0      0     c008f0b0 00000000 S migration/2
root      19    2     0      0     c008f0b0 00000000 S ksoftirqd/2
root      20    2     0      0     c00831a8 00000000 S kworker/2:0
root      21    2     0      0     c00831a8 00000000 S kworker/2:0H
root      22    2     0      0     c008f0b0 00000000 S watchdog/3
root      23    2     0      0     c008f0b0 00000000 S migration/3
root      24    2     0      0     c008f0b0 00000000 S ksoftirqd/3
root      25    2     0      0     c00831a8 00000000 S kworker/3:0
root      26    2     0      0     c00831a8 00000000 S kworker/3:0H
root      27    2     0      0     c008f0b0 00000000 S watchdog/4
root      28    2     0      0     c008f0b0 00000000 S migration/4
root      29    2     0      0     c008f0b0 00000000 S ksoftirqd/4
root      30    2     0      0     c00831a8 00000000 S kworker/4:0
root      31    2     0      0     c00831a8 00000000 S kworker/4:0H
root      32    2     0      0     c0082b9c 00000000 S khelper
root      33    2     0      0     c0082b9c 00000000 S suspend
root      34    2     0      0     c0082b9c 00000000 S lc1160
root      35    2     0      0     c0082b9c 00000000 S writeback
root      36    2     0      0     c0082b9c 00000000 S bioset
root      37    2     0      0     c0082b9c 00000000 S crypto
root      38    2     0      0     c0082b9c 00000000 S kblockd
root      39    2     0      0     c02b1520 00000000 S khubd
root      40    2     0      0     c0082b9c 00000000 S otg_switch
root      41    2     0      0     c00831a8 00000000 S kworker/0:1
root      42    2     0      0     c00831a8 00000000 S kworker/1:1
root      44    2     0      0     c0337824 00000000 S ion_system_heap
root      45    2     0      0     c0337824 00000000 S lc_ion_system_h
root      46    2     0      0     c0082b9c 00000000 S galcore workque
root      47    2     0      0     c008cf34 00000000 S galcore daemon 
root      48    2     0      0     c0082b9c 00000000 S gpio keys
root      49    2     0      0     c0082b9c 00000000 S snd_timer
root      50    2     0      0     c00c307c 00000000 S khungtaskd
root      51    2     0      0     c00feb20 00000000 S kswapd0
root      52    2     0      0     c0161868 00000000 S fsnotify_mark
root      66    2     0      0     c0082b9c 00000000 S comip
root      67    2     0      0     c0082b9c 00000000 S comip
root      69    2     0      0     c0082b9c 00000000 S uether
root      70    2     0      0     c032b008 00000000 S mmcqd/0
root      71    2     0      0     c032b008 00000000 S mmcqd/0boot0
root      72    2     0      0     c032b008 00000000 S mmcqd/0boot1
root      73    2     0      0     c032b008 00000000 S mmcqd/0rpmb
root      74    2     0      0     c032b008 00000000 S mmcqd/1
root      75    2     0      0     c0082b9c 00000000 S binder
root      76    2     0      0     c008f0e8 00000000 S wdt/0
root      77    2     0      0     c008f0e8 00000000 S wdt/1
root      78    2     0      0     c008f0e8 00000000 S wdt/2
root      79    2     0      0     c008f0e8 00000000 S wdt/3
root      80    2     0      0     c008f0b0 00000000 S wdt/4
root      81    2     0      0     c0082b9c 00000000 S deferwq
root      82    2     0      0     c0082b9c 00000000 S f_mtp
root      83    2     0      0     c02e61f4 00000000 S file-storage
root      84    2     0      0     c00831a8 00000000 S kworker/0:2
root      85    1     524    228   c013a7b0 0001a8bc S /sbin/ueventd
root      86    2     0      0     c00831a8 00000000 S kworker/0:1H
root      87    2     0      0     c01cd54c 00000000 S jbd2/mmcblk0p5-
root      88    2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      91    2     0      0     c01cd54c 00000000 S jbd2/mmcblk0p11
root      92    2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      93    2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      105   2     0      0     c00831a8 00000000 S kworker/3:1
root      106   2     0      0     c00831a8 00000000 S kworker/2:1
root      110   1     936    456   c0251468 b6e8f318 S /system/bin/mksh
root      116   2     0      0     c0082b9c 00000000 S isp
root      121   2     0      0     c0082b9c 00000000 S aecgc
root      128   2     0      0     c00831a8 00000000 S kworker/4:1
root      133   2     0      0     c01cd54c 00000000 S jbd2/mmcblk0p14
root      134   2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      140   2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      146   2     0      0     c01cd54c 00000000 S jbd2/mmcblk0p12
root      147   2     0      0     c0082b9c 00000000 S ext4-dio-unwrit
root      176   2     0      0     c00831a8 00000000 S kworker/1:1H
root      199   1     1624   288   c013a7b0 00013308 S busybox
root      200   1     1624   296   c03b2238 00013816 S busybox
root      237   1     43400  5104  ffffffff b6ebded4 S /system/bin/dji_hdvt_uav
root      241   1     24204  4048  ffffffff b6eb6ed4 S /system/bin/dji_network
root      243   1     31368  4468  ffffffff b6ea1370 S /system/bin/dji_sw_uav
root      245   1     14580  3088  ffffffff b6eb8370 S /system/bin/dji_monitor
root      247   1     47772  9008  ffffffff b6e87f5c S /system/bin/dji_sys
root      250   1     31904  20492 ffffffff b6daaed4 S /system/bin/dji_blackbox
root      252   1     212168 23572 ffffffff b6f4cf5c S /system/bin/dji_vision
root      253   1     1752   1232  c0380f6c b6f122d0 S debuggerd
root      322   1     896    400   c013a7b0 b6eb6728 S logcat
root      323   1     1260   492   c0131e04 b6f32318 S grep
root      356   1     936    448   c013a7b0 b6ec4728 S /system/bin/sh
root      433   1     896    404   c013a7b0 b6f65728 S logcat
root      512   1     944    456   c013a7b0 b6ef0728 S /system/bin/sh
root      514   1     936    444   c013a7b0 b6eb8728 S /system/bin/sh
root      522   1     67852  10252 ffffffff b6d73d48 S /data/python_files/bin/python
root      545   514   896    400   c013a7b0 b6f73728 S logcat
root      546   514   1248   480   c0131e04 b6eef318 S grep
root      624   2     0      0     c0082b9c 00000000 S cfg80211
root      652   2     0      0     c0082b9c 00000000 S ath6kl
root      692   2     0      0     c0326b38 00000000 S ksdioirqd/mmc2
wifi      924   241   3476   2008  c013a7b0 b6e53728 S /system/bin/wpa_supplicant
dhcp      1229  1     1040   356   c013a7b0 b6ee2814 S dhcpcd
root      4956  2     0      0     c00831a8 00000000 S kworker/u10:2
root      7159  522   0      0     c006deec 00000000 Z adb_en.sh
root      7169  1     5692   304   ffffffff 0001902c S /sbin/adbd
root      7267  1     1624   272   c013a7b0 00013308 S busybox
root      7563  14337 1248   452   00000000 b6ed2318 R ps
root      14337 7169  952    492   c007bbe8 b6ed5160 S /system/bin/sh
root      14509 2     0      0     c00831a8 00000000 S kworker/u10:0
root      18582 1     150484 8408  ffffffff b6d98ed4 S /system/bin/dji_camera
root      31067 2     0      0     c00831a8 00000000 S kworker/u10:1
root      31629 2     0      0     c00831a8 00000000 S kworker/0:0
root      32465 2     0      0     c00831a8 00000000 S kworker/u10:3
```

## Reference

- [机甲大师S1 Python沙箱逃逸](https://tmcdcgeek.club/2019/09/28/robomaster/)
- [RoboMaster Developer Guide](https://robomaster-dev.readthedocs.io/zh_CN/latest/)
- [bga/robomasters1](https://git.bug-br.org.br/bga/robomasters1)

https://stackoverflow.com/questions/9285903/adb-shell-giving-bad-mode-when-executing-chmod-under-su
https://github.com/veandco/go-sdl2-examples/tree/master/examples/android
