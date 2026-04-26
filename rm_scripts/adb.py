# dji safe modules
import event_client
import rm_ctrl
import rm_define
import rm_log

logger = rm_log.dji_scratch_logger_get()
event = event_client.EventClient()
modulesStatus_ctrl = rm_ctrl.ModulesStatusCtrl(event)
gun_ctrl = rm_ctrl.GunCtrl(event)
armor_ctrl = rm_ctrl.ArmorCtrl(event)
vision_ctrl = rm_ctrl.VisionCtrl(event)
chassis_ctrl = rm_ctrl.ChassisCtrl(event)
gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
robot_ctrl = rm_ctrl.RobotCtrl(event, chassis_ctrl, gimbal_ctrl)
log_ctrl = rm_ctrl.LogCtrl(event)
# scratch mode only
led_ctrl = rm_ctrl.LedCtrl(event)
media_ctrl = rm_ctrl.MediaCtrl(event)
# need replaced when app changed the method name
time = rm_ctrl.RobotTools(event)
tools = rm_ctrl.RobotTools(event)
debug_ctrl = rm_ctrl.DebugCtrl(event)
mobile_ctrl = rm_ctrl.MobileCtrl(event)
blaster_ctrl = gun_ctrl
AI_ctrl = vision_ctrl

show_msg = log_ctrl.show_msg
print_msg = log_ctrl.print_msg
info_msg = log_ctrl.info_msg
debug_msg = log_ctrl.debug_msg
error_msg = log_ctrl.error_msg
fatal_msg = log_ctrl.fatal_msg
print = print_msg

robot_mode = rm_define.robot_mode
chassis_status = rm_define.chassis_status
gimbal_status = rm_define.gimbal_status
detection_type = rm_define.detection_type
detection_func = rm_define.detection_func
led_effect = rm_define.led_effect
led_position = rm_define.led_position
pwm_port = rm_define.pwm_port
line_color = rm_define.line_color

print("enable sdk mode: %s" % robot_ctrl.enable_sdk_mode())
sdk_ctrl = rm_ctrl.SDKCtrl(event)
sdk_ctrl.sdk_on()
print("enable stream: %s" % sdk_ctrl.stream_on())

__import__ = rm_log.__dict__["__builtins__"]["__import__"]


def import_string(import_name):
    """
    import function for unsafe modules
    """
    import_name = str(import_name).replace(":", ".")
    try:
        __import__(import_name)
    except ImportError:
        if "." not in import_name:
            raise
    else:
        return sys.modules[import_name]

    module_name, obj_name = import_name.rsplit(".", 1)
    module = __import__(module_name, globals(), locals(), [obj_name])
    try:
        return getattr(module, obj_name)
    except AttributeError as e:
        raise ImportError(e)


os = __import__("os")
print("os.environ", os.environ)

sys = __import__("sys")
print("sys.platform: ", sys.platform)
print("sys.version: ", sys.version)
print("sys.executable: ", sys.executable)

print("globals", globals())
print("locals", locals())


def serve_adb():
    print("starting adb")
    subprocess = import_string("subprocess")
    proc = subprocess.Popen(
        "/system/bin/adb_en.sh",
        shell=True,
        executable="/system/bin/sh",
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


serve_adb()
