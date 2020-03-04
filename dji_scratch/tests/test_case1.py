import event_client
import rm_ctrl
import tools
import rm_define
import time
import rm_log

logger_file = rm_log.logger_file_out_path_generate('dji_scratch')
logger = rm_log.dji_scratch_logger_get()
logger = rm_log.logger_init(logger, logger_file, rm_log.DEBUG)
logger.info('DJI_SCRATCH: create log file is %s' %logger_file)



event = event_client.EventClient()
vision_ctrl = rm_ctrl.VisionCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
led_ctrl = rm_ctrl.LedCtrl(event)
tools.wait(50)

def ready():
    vision_ctrl.register_event(globals())

def start():
    while True:
        gun_ctrl.fire()
        time.sleep(10)

def vision_recognition_marker_all(msg):
    print(msg)

try:
    ready()
except Exception as e:
    print(e.message)
    pass

try:
    start()
except Exception as e:
    print(e.message)
    pass

while(event.is_wait()):
    tools.wait(100)

gun_ctrl.stop()
vision_ctrl.stop()
del gun_ctrl
del vision_ctrl

event.stop()
