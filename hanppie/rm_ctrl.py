import functools
import math
import re
import threading
import time

from . import rm_builtins
from . import rm_define
from . import rm_log
from . import rm_module
from . import tools

logger = rm_log.dji_scratch_logger_get()

CHECK_VALUE_TYPE = tools.check_value_type
CHECK_VALUE_RANGE = tools.check_value_range
CHECK_VALUE_RANGE_AND_TYPE = tools.check_value_range_and_type
CHECK_VALUE_IN_ENUM_LIST = tools.check_value_in_enum_list


def dummy_callback(*args, **kw):
    pass


def get_task_dict(task_identify, event_type, *args, **kw):
    task_dict = {}
    task_dict["task_identify"] = task_identify
    task_dict["event_type"] = event_type
    task_dict["args"] = args
    task_dict["kw"] = kw
    return task_dict


def get_result(d):
    result = True
    if type(d) == tuple and len(d) == 4:
        result = d[3]
    elif type(d) == dict:
        try:
            result = d["kw"]["result"]
        except:
            result = False
    else:
        result = d
    return (False, True)[result == 0 or result == True]


def event_register(func):
    @functools.wraps(func)
    def wrapper(*args, **kw):
        ctrl = args[0]
        if ctrl.event_client.script_state.check_stop():
            # ctrl.event_client.script_state.reset_stop_flag()
            raise Exception("SCRIPT_CTRL: received exit cmd, raise exception")

        task_dict = func(*args, **kw)
        # register interrupted func to set block result
        # ctrl.interrupt_func_register(ctrl.event_client.script_state.set_block_running_state, tools.get_err_code(rm_define.DUSS_TASK_INTERRUPT))
        # create a task
        robot_task = rm_module.RobotEvent(
            task_dict["task_identify"], task_dict["event_type"], ctrl
        )
        # wait for task finish
        task_finish, has_event = robot_task.wait_for_complete(60)
        # not a task or task is not interrupted
        result = rm_define.DUSS_SUCCESS
        if task_finish == True:
            # get results of func's return
            if "result" in task_dict["kw"].keys():
                result = task_dict["kw"]["result"]
            if "err_code" in task_dict["kw"].keys():
                result = task_dict["kw"]["err_code"]
            ctrl.event_client.script_state.set_block_running_state(result)

        return has_event, task_finish, robot_task, result

    return wrapper


class RobotCtrlBases(object):
    def __init__(self):
        self.action_state = {}
        self.mutex_action = {}
        self.action_interrupted = []
        self.action_finished = {}
        self.action_finished["func"] = None
        self.exit_flag = False

    def set_actions(self, actions):
        for act in actions:
            self.action_state[act] = False
            self.mutex_action[act] = set()

    def remove_actions(self, actions):
        for act in actions:
            if act in self.action_state.keys():
                self.action_state.pop(act)
                self.mutex_action.pop(act)

    def set_mutex_action(self, action, mutex_action):
        self.mutex_action[action] = set(mutex_action)

    def remove_mutex_action(self, action, mutex_action):
        if action in self.mutex_action.keys():
            self.mutex_action[action].discard(mutex_action)

    def check_action_state(self, action):
        if action in self.action_state.keys():
            return self.action_state[action]

    def set_action_state(self, action):
        if action not in self.action_state.keys():
            return
        for act in self.action_state.keys():
            if self.action_state[act] and act in self.mutex_action[action]:
                logger.info("current action: %s, stop action: %s" % (action, act))
                self.action_state[act] = False

        self.action_state[action] = True

    def reset_action_state(self, action):
        if action in self.action_state.keys():
            self.action_state[action] = False

    def reset_all_action_state(self):
        for act in self.action_state.keys():
            self.action_state[act] = False

    def interrupt_func_register(self, func, *args, **fw):
        interrupted_func = {}
        interrupted_func["func"] = func
        interrupted_func["args"] = args
        interrupted_func["fw"] = fw
        self.action_interrupted.append(interrupted_func)

    def finished_func_register(self, func, *args, **fw):
        self.action_finished["func"] = func
        self.action_finished["args"] = args
        self.action_finished["fw"] = fw

    def interrupt_func_unregister(self):
        self.action_interrupted = []

    def finished_func_unregister(self):
        self.action_finished["func"] = None

    def stop_with_interrupted(self):
        for interrupted_func in self.action_interrupted:
            if interrupted_func["func"] != None:
                func = interrupted_func["func"]
                func(*interrupted_func["args"], **interrupted_func["fw"])

    def stop_with_finish(self):
        if self.action_finished["func"] != None:
            func = self.action_finished["func"]
            func(*self.action_finished["args"], **self.action_finished["fw"])


class RobotCtrlTool(RobotCtrlBases):
    def __init__(self, event_client):
        super().__init__()
        self.event_client = event_client
        self._sleep_func_dict = {50: self._robot_sleep_50ms, 10: self._robot_sleep_10ms}

    def reinit_event_client(self, event_client):
        self.event_client = event_client

    # wrapper for robot_sleep
    def sleep(self, sleep_time):
        CHECK_VALUE_RANGE_AND_TYPE(sleep_time, 0, 60 * 60, int, float)
        t1 = time.time()
        self.robot_sleep(sleep_time * 1000, None, (), {})

    # wrapper for robot_sleep
    def wait(self, sleep_time):
        self._robot_sleep_0ms()
        self.robot_sleep(sleep_time, None, (), {})

    def pass_func(self):
        self._robot_sleep_0ms()

    # sleep (s), only interrupted by condition, time ticks 50 ms, the unit of sleep_time is ms
    def robot_sleep(self, sleep_time, condition_func=None, *args, **kw):
        has_condition = False
        t_count = 0
        t_unit = 1
        if sleep_time >= 50:
            t_unit = 50
        else:
            t_unit = 10

        t_sleep = sleep_time
        while True:
            # interrupted by condition
            if condition_func != None and condition_func(*args, **kw):
                has_condition = True
                break
            t_last = time.time()
            if t_sleep >= t_unit:
                self._sleep_func_dict[t_unit]()
            else:
                if t_sleep > 0:
                    self._robot_sleep_xms(t_sleep)
                break
            t_cur = time.time()
            t_delt = (t_cur - t_last) * 1000
            t_sleep -= t_delt

        return has_condition

    # sleep (s), can be interrupted by event or condition
    def robot_sleep_interruptable(
        self, sleep_time, func_before_event=None, condition_func=None, *args, **kw
    ):
        has_condition, has_event = False, False
        # register func which should be process before event
        self.interrupt_func_register(func_before_event)
        for i in range(int(sleep_time / 10)):
            # interrupted by event
            has_event, _, _, _ = self._robot_sleep_10ms()
            if has_event:
                break
            # interrupted by condition
            if condition_func != None and condition_func(*args, **kw):
                has_condition = True
                break
        self.interrupt_func_unregister()
        return has_condition, has_event

    @event_register
    def _robot_sleep_xms(self, t):
        tools.wait(t)
        return get_task_dict(None, "sleep", (t), {})

    @event_register
    def _robot_sleep_50ms(self):
        tools.wait(50)
        return get_task_dict(None, "sleep", (), {})

    @event_register
    def _robot_sleep_10ms(self):
        tools.wait(10)
        return get_task_dict(None, "sleep", (), {})

    @event_register
    def _robot_sleep_0ms(self):
        return get_task_dict(None, "sleep", (), {})


class ChassisCtrl(RobotCtrlTool):
    CHASSIS_IMPACT_DETECTION_STR = "chassis_impact_detection"

    def __init__(self, event_client):
        super().__init__(event_client)
        self.chassis = rm_module.Chassis(event_client)
        self.event_client = event_client
        self.attr_dict = {
            "mode": rm_define.chassis_sdk_free_mode,
            "m_speed": rm_define.speed_base_default,
            "r_speed": rm_define.speed_yaw_default,
            "f_angle": rm_define.angle_follow_default,
            "m_dire": rm_define.chassis_front,
            "r_dire": rm_define.clockwise,
            "stick_overlay_enable": rm_define.stick_overlay_disable,
            "stick_overlay_need_update_mode": True,
            "sdk_x_speed": 0,
            "sdk_y_speed": 0,
            "sdk_z_speed": 0,
            "sdk_w1_speed": 0,
            "sdk_w2_speed": 0,
            "sdk_w3_speed": 0,
            "sdk_w4_speed": 0,
        }
        self.status_dict = {
            "cur_speed_wheel1": 0,
            "cur_speed_wheel2": 0,
            "cur_speed_wheel3": 0,
            "cur_speed_wheel4": 0,
            "cur_speed_gx": 0,
            "cur_speed_gy": 0,
            "cur_speed_bx": 0,
            "cur_speed_by": 0,
            "cur_speed_wz": 0,
            "cur_attitude_pitch": 0,
            "cur_attitude_roll": 0,
            "cur_attitude_yaw": 0,
            "cur_position_x": 0,
            "cur_position_y": 0,
            "init_attitude_yaw": 0,
            "init_position_x": 0,
            "init_position_y": 0,
        }
        self.attitude_status_dict = {
            "static_flag": 0,
            "uphill_flag": 0,
            "downhill_flag": 0,
            "on_slope_flag": 0,
            "pick_up_flag": 0,
            "slip_flag": 0,
            "impact_x_flag": 0,
            "impact_y_flag": 0,
            "impact_z_flag": 0,
            "roll_over": 0,
            "hill_static": 0,
            "impact_time": 0,
        }
        self.set_actions(["move", "rotate", "move_rotate"])
        self.set_mutex_action("move", ["rotate", "move_rotate"])
        self.set_mutex_action("rotate", ["move", "move_rotate"])
        self.set_mutex_action("move_rotate", ["move", "rotate"])

        self.recognition_event_cb = []

        self.update_attitude_status_count = 0
        self.update_attitude_status_unit = RobotCtrl.GIMBAL_CHASSIS_SUB_INFO_PUSH_FREQ
        self.sdk_info_push_callback = None
        self.sdk_info_push_freq_default = 5
        self.sdk_info_push_freq_list = [1, 5, 10, 20, 30, 50]

        self.sdk_position_info_push_attr = {
            "enable_flag": False,
            "freq_count": self.sdk_info_push_freq_default,
        }
        self.sdk_attitude_info_push_attr = {
            "enable_flag": False,
            "freq_count": self.sdk_info_push_freq_default,
        }
        self.sdk_status_info_push_attr = {
            "enable_flag": False,
            "freq_count": self.sdk_info_push_freq_default,
        }

        # calc freq LCM (lowest common multiple)
        self.sdk_info_push_freq_lcm = 1
        for i in self.sdk_info_push_freq_list:
            self.sdk_info_push_freq_lcm = int(
                i
                * self.sdk_info_push_freq_lcm
                / math.gcd(i, self.sdk_info_push_freq_lcm)
            )

    def init(self):
        # self.sub_attitude_speed_position_info(self.process_sub_info_callback)
        self.chassis.attitude_event_register(self.attitute_status_process)
        pass

    def enable_speed_limit_mode(self):
        logger.info("enable_speed_limit_mode")
        self.chassis.set_speed_limit_flag(True)

    def disable_speed_limit_mode(self):
        logger.info("disable_speed_limit_mode")
        self.chassis.set_speed_limit_flag(False)

    # act immediately
    @event_register
    def set_mode(self, mode):
        logger.info("CHASSIS_CTRL: set_mode = %s" % (mode))
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["mode"] = mode
        work_mode = rm_define.chassis_fpv_mode
        if (
            mode == rm_define.chassis_sdk_free_mode
            or mode == rm_define.chassis_sdk_follow_mode
        ):
            work_mode = rm_define.chassis_sdk_mode
        duss_result = self.chassis.set_work_mode(work_mode)
        duss_result = self._set_chassis_stop_depend_mode()
        return get_task_dict(None, "attr_set", (mode), result=duss_result)

    def set_mode_attr(self, mode):
        self.attr_dict["mode"] = mode

    @event_register
    def enable_stick_overlay(self):
        duss_result = rm_define.DUSS_SUCCESS
        if (
            self.attr_dict["stick_overlay_enable"]
            != rm_define.stick_overlay_and_axes_enable
        ):
            logger.info(
                "CHASSIS_CTRL: enable stick overlay, %s"
                % rm_define.stick_overlay_and_axes_enable
            )
            self.attr_dict[
                "stick_overlay_enable"
            ] = rm_define.stick_overlay_and_axes_enable
            duss_result = self.chassis.set_stick_overlay(
                rm_define.stick_overlay_and_axes_enable
            )
        else:
            logger.info("CHASSIS_CTRL: stick overlay has already enabled")
        return get_task_dict(None, "arrt_set", (), result=duss_result)

    @event_register
    def disable_stick_overlay(self):
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["stick_overlay_enable"] != rm_define.stick_overlay_disable:
            logger.info("CHASSIS_CTRL: disable stick overlay")
            self.attr_dict["stick_overlay_enable"] = rm_define.stick_overlay_disable
            duss_result = self.chassis.set_stick_overlay(
                rm_define.stick_overlay_disable
            )
        else:
            logger.info("CHASSIS_CTRL: stick overlay has been already disabled")
        return get_task_dict(None, "arrt_set", (), result=duss_result)

    # act immediately
    @event_register
    def set_wheel_speed(self, w2, w1, w3, w4):
        CHECK_VALUE_RANGE_AND_TYPE(
            w1, rm_define.speed_wheel_min, rm_define.speed_wheel_max, int, float
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            w2, rm_define.speed_wheel_min, rm_define.speed_wheel_max, int, float
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            w3, rm_define.speed_wheel_min, rm_define.speed_wheel_max, int, float
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            w4, rm_define.speed_wheel_min, rm_define.speed_wheel_max, int, float
        )
        w2 = -w2
        w3 = -w3
        w1 = int(round(w1))
        w2 = int(round(w2))
        w3 = int(round(w3))
        w4 = int(round(w4))
        logger.info("CHASSIS_CTRL: set_wheel_speed = %s %s %s %s" % (w1, w2, w3, w4))
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["mode"] != rm_define.chassis_sdk_follow_mode:
            duss_result = self.chassis.set_wheel_speed(w1, w2, w3, w4)
            if w1 == w2 == w3 == w4 == 0:
                self.attr_dict["stick_overlay_need_update_mode"] = True
            else:
                self.set_action_state("move")
                self.attr_dict["stick_overlay_need_update_mode"] = False
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "attr_set",
                (w1, w2, w3, w4),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )

        return get_task_dict(None, "attr_set", (w1, w2, w3, w4), result=duss_result)

    # attribute setting, if 'move' action is running, speed change immediately
    @event_register
    def set_trans_speed(self, speed):
        logger.info("CHASSIS_CTRL: set move speed, speed is " + str(speed))
        CHECK_VALUE_RANGE_AND_TYPE(speed, 0, rm_define.speed_max, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["m_speed"] = tools.data_limit(speed, 0, rm_define.speed_max)
        self.attr_dict["cus_speed"] = tools.data_limit(speed, 0, rm_define.speed_max)
        if self.check_action_state("move"):
            duss_result = self._move_ctrl(time.time())
        return get_task_dict(None, "attr_set", (speed), result=duss_result)

    # attribute setting, if 'move' action is running, direction change immediately
    @event_register
    def set_move_direction(self, direction):
        logger.info("CHASSIS_CTRL: set move direction, direction is " + str(direction))
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["m_dire"] != direction:
            self._set_chassis_stop()
            tools.wait(350)
        self.attr_dict["m_dire"] = direction
        if self.check_action_state("move"):
            duss_result = self._move_ctrl(time.time())
        return get_task_dict(None, "attr_set", (direction), result=duss_result)

    # attribute setting, if 'rotate' action is running, yaw_speed change immediately
    @event_register
    def set_rotate_speed(self, speed):
        logger.info("CHASSIS_CTRL: set rotate speed, speed is " + str(speed))
        CHECK_VALUE_RANGE_AND_TYPE(speed, 0, rm_define.speed_yaw_max, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["r_speed"] = tools.data_limit(speed, 0, rm_define.speed_yaw_max)
        if self.check_action_state("rotate"):
            duss_result = self._rotate_ctrl(time.time())
        return get_task_dict(None, "attr_set", (speed), result=duss_result)

    # continnous action, mutex action
    @event_register
    def move(self, direction_angle):
        logger.info("CHASSIS_CTRL: moving, direction_angle is " + str(direction_angle))
        CHECK_VALUE_RANGE_AND_TYPE(direction_angle, -180, 180, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["m_dire"] = rm_define.chassis_customize_direction
        self.attr_dict["cus_speed"] = self.attr_dict["m_speed"]
        self.attr_dict["cus_degree"] = direction_angle
        self.set_action_state("move")
        duss_result = self._move_ctrl(time.time())
        return get_task_dict(
            None, "action_immediate", (direction_angle), result=duss_result
        )

    # continnous action, mutex action
    @event_register
    def move_with_time(self, direction_angle, time_wait):
        logger.info(
            "CHASSIS_CTRL: moving with time, direction_angle is "
            + str(direction_angle)
            + " time is "
            + str(time_wait)
        )
        CHECK_VALUE_RANGE_AND_TYPE(direction_angle, -180, 180, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(time_wait, 0, 20, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["m_dire"] = rm_define.chassis_customize_direction
        self.attr_dict["cus_speed"] = self.attr_dict["m_speed"]
        self.attr_dict["cus_degree"] = direction_angle
        self.set_action_state("move")
        duss_result = self._move_ctrl(time.time(), time_wait)
        self.reset_action_state("move")
        return get_task_dict(
            None, "action_with_time", (direction_angle, time_wait), result=duss_result
        )

    # continnous action, mutex action
    @event_register
    def move_with_distance(self, direction_angle, distance):
        logger.info(
            "CHASSIS_CTRL: moving with time, direction_angle is "
            + str(direction_angle)
            + " distance is "
            + str(distance)
        )
        CHECK_VALUE_RANGE_AND_TYPE(direction_angle, -180, 180, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(distance, 0, 5, int, float)
        duss_result = rm_define.DUSS_SUCCESS

        if self.attr_dict["m_speed"] == 0:
            return get_task_dict(
                None, "no_task", (direction_angle, distance), result=duss_result
            )

        self.attr_dict["m_dire"] = rm_define.chassis_customize_direction
        self.attr_dict["cus_speed"] = self.attr_dict["m_speed"]
        self.attr_dict["cus_degree"] = direction_angle
        distance = tools.data_limit(distance, 0, rm_define.move_distance_max) * 100

        pos_x = math.cos(self.attr_dict["cus_degree"] * math.pi / 180.0) * distance
        pos_y = math.sin(self.attr_dict["cus_degree"] * math.pi / 180.0) * distance

        pos_x = int(pos_x)
        pos_y = int(pos_y)

        self.set_action_state("move")
        self.interrupt_func_register(self.position_cmd_interrupt_callback)
        self.finished_func_register(self.position_cmd_finished_callback)

        duss_result, identify = self.chassis.set_position_cmd(
            0,
            0,
            pos_x,
            pos_y,
            0,
            int(self.attr_dict["m_speed"] * 100),
            int(self.attr_dict["r_speed"] * 10),
            cmd_type=rm_define.TASK,
        )

        if duss_result == rm_define.DUSS_SUCCESS:
            return get_task_dict(
                identify, "task", (direction_angle, distance), result=duss_result
            )
        else:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (direction_angle, distance), result=duss_result
            )

    @event_register
    def move_with_speed(self, speed_x=0, speed_y=0, speed_z=0):
        logger.info(
            "CHASSIS_CTRL: moveing with speed, speed is %s %s %s"
            % (speed_x, speed_y, speed_z)
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            speed_x, rm_define.speed_min, rm_define.speed_max, int, float
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            speed_y, rm_define.speed_min, rm_define.speed_max, int, float
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            speed_z, rm_define.speed_yaw_min, rm_define.speed_yaw_max, int, float
        )
        duss_result = rm_define.DUSS_SUCCESS
        self._set_chassis_speed(speed_x, speed_y, speed_z, True)
        self.set_action_state("move")
        return get_task_dict(
            None, "action_immediate", (speed_x, speed_y, speed_z), result=duss_result
        )

    def position_cmd_interrupt_callback(self):
        self.chassis.set_position_cmd_stop()
        self._set_chassis_stop_depend_mode()

    def position_cmd_finished_callback(self):
        self.reset_action_state("move")
        self._set_chassis_stop_depend_mode()

    @event_register
    def move_degree_with_speed(self, speed, degree):
        logger.info("CHASSIS_CTRL: move_degree_with_speed")
        CHECK_VALUE_RANGE_AND_TYPE(speed, 0, rm_define.speed_max, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(degree, -180, 180, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["m_dire"] = rm_define.chassis_customize_direction
        self.attr_dict["cus_speed"] = speed
        self.attr_dict["cus_degree"] = degree
        self.set_action_state("move")
        duss_result = self._move_ctrl(time.time())
        return get_task_dict(
            None, "action_immediate", (speed, degree), result=duss_result
        )

    # continnous action, mutex action
    @event_register
    def rotate(self, direction):
        logger.info("CHASSIS_CTRL: rotating, diection is" + str(direction))
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.clockwise": rm_define.clockwise,
                "rm_define.anticlockwise": rm_define.anticlockwise,
            }
        )
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["mode"] != rm_define.chassis_sdk_follow_mode:
            self.attr_dict["r_dire"] = direction
            self.set_action_state("rotate")
            duss_result = self._rotate_ctrl(time.time())
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "action_immediate",
                (direction),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )
        return get_task_dict(None, "action_immediate", (direction), result=duss_result)

    # continnous action, mutex action
    @event_register
    def rotate_with_time(self, direction, time_wait):
        logger.info(
            "CHASSIS_CTRL: rotating with time, direction is "
            + str(direction)
            + " time is "
            + str(time_wait)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.clockwise": rm_define.clockwise,
                "rm_define.anticlockwise": rm_define.anticlockwise,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(time_wait, 0, 20, int, float)
        duss_result = rm_define.DUSS_SUCCESS

        if self.attr_dict["mode"] != rm_define.chassis_sdk_follow_mode:
            self.attr_dict["r_dire"] = direction
            self.set_action_state("rotate")
            duss_result = self._rotate_ctrl(time.time(), time_wait)
            self.reset_action_state("rotate")
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "action_with_time",
                (direction, time_wait),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )
        return get_task_dict(
            None, "action_with_time", (direction, time_wait), result=duss_result
        )

    # continnous action, mutex action
    @event_register
    def rotate_with_degree(self, direction, degree):
        logger.info(
            "CHASSIS_CTRL: rotating with degree, direction is "
            + str(direction)
            + " degree is "
            + str(degree)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.clockwise": rm_define.clockwise,
                "rm_define.anticlockwise": rm_define.anticlockwise,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(degree, 0, 1800, int, float)
        duss_result = rm_define.DUSS_SUCCESS

        if self.attr_dict["mode"] != rm_define.chassis_sdk_follow_mode:
            if self.attr_dict["r_speed"] == 0:
                return get_task_dict(
                    None, "no_task", (direction, degree), result=duss_result
                )
            self.attr_dict["r_dire"] = direction
            degree = tools.data_limit(degree, 0, rm_define.rotate_degree_max) * 10
            if direction == rm_define.anticlockwise:
                degree = degree
            elif direction == rm_define.clockwise:
                degree = -degree
            else:
                pass
            self.set_action_state("rotate")
            self.interrupt_func_register(self.chassis.set_position_cmd_stop)
            self.finished_func_register(self.reset_action_state, "rotate")
            duss_result, identify = self.chassis.set_position_cmd(
                0,
                0,
                0,
                0,
                degree,
                int(self.attr_dict["m_speed"] * 100),
                int(self.attr_dict["r_speed"] * 10),
                cmd_type=rm_define.TASK,
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (direction, degree), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (direction, degree), result=duss_result
                )
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "no_task",
                (direction, degree),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )

    # continnous action, mutex action
    @event_register
    def move_and_rotate(self, degree, direction):
        logger.info(
            "CHASSIS_CTRL: move and rotate, direction is "
            + str(direction)
            + "degree is "
            + str(degree)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.clockwise": rm_define.clockwise,
                "rm_define.anticlockwise": rm_define.anticlockwise,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(degree, -180, 180, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        self.attr_dict["r_dire"] = direction
        self.set_action_state("move_rotate")
        rotate_speed = self.attr_dict["r_speed"]
        if degree < 0:
            degree = 360 + degree
        speed_y = math.sin(degree * math.pi / 180) * self.attr_dict["m_speed"]
        speed_x = math.cos(degree * math.pi / 180) * self.attr_dict["m_speed"]

        err_code = None
        if self.attr_dict["mode"] != rm_define.chassis_sdk_follow_mode:
            if direction == rm_define.anticlockwise:
                rotate_speed = -rotate_speed
            elif direction == rm_define.clockwise:
                rotate_speed = rotate_speed
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            rotate_speed = 0
            err_code = rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_follow_mode, the function is invalid"
            )

        duss_result = self._set_chassis_speed(speed_x, speed_y, rotate_speed)

        if err_code:
            return get_task_dict(
                None,
                "action_immediate",
                (degree, direction),
                result=duss_result,
                err_code=err_code,
            )
        else:
            return get_task_dict(
                None, "action_immediate", (degree, direction), result=duss_result
            )

    # act immediately
    @event_register
    def stop(self):
        logger.info("CHASSIS_CTRL: stop")
        duss_result = rm_define.DUSS_SUCCESS
        self._set_chassis_stop_depend_mode()
        self.reset_all_action_state()
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def exit(self):
        self.chassis.attitude_event_unregister()
        self.reset_pwm_value()
        # self.unsub_attitude_speed_position_info()

    @event_register
    def set_pwm_value(self, comp, p):
        logger.info("CHASSIS_CTRL: set pwm value, comp is %s, p is %s" % (comp, p))
        CHECK_VALUE_RANGE_AND_TYPE(p, 0, 100, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        mode = 0
        if comp & rm_define.pwm1:
            mode = mode + rm_define.pwm1
        if comp & rm_define.pwm2:
            mode = mode + rm_define.pwm2
        if comp & rm_define.pwm3:
            mode = mode + rm_define.pwm3
        if comp & rm_define.pwm4:
            mode = mode + rm_define.pwm4
        if comp & rm_define.pwm5:
            mode = mode + rm_define.pwm5
        if comp & rm_define.pwm6:
            mode = mode + rm_define.pwm6
        p = int(p * 10)
        duss_result = self.chassis.set_pwm_value(mode, p)
        return get_task_dict(None, "action_immediate", (comp, p), result=duss_result)

    @event_register
    def set_pwm_freq(self, comp, p):
        logger.info("CHASSIS_CTRL: set pwm freq, comp is %s, p is %s" % (comp, p))
        duss_result = rm_define.DUSS_SUCCESS
        mode = 0
        if comp & rm_define.pwm1:
            mode = mode + rm_define.pwm1
        if comp & rm_define.pwm2:
            mode = mode + rm_define.pwm2
        if comp & rm_define.pwm3:
            mode = mode + rm_define.pwm3
        if comp & rm_define.pwm4:
            mode = mode + rm_define.pwm4
        if comp & rm_define.pwm5:
            mode = mode + rm_define.pwm5
        if comp & rm_define.pwm6:
            mode = mode + rm_define.pwm6
        duss_result = self.chassis.set_pwm_freq(mode, p)
        return get_task_dict(None, "action_immediate", (comp, p), result=duss_result)

    def reset_pwm_value(self):
        logger.info("CHASSIS_CTRL: reset pwm value")
        self.set_pwm_value(rm_define.pwm_all, 7.5)

    @event_register
    def set_follow_gimbal_offset(self, degree):
        logger.info("CHASSIS_CTRL: set follow gimbal offset, the degree is %f", degree)
        CHECK_VALUE_RANGE_AND_TYPE(degree, -180, 180, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["mode"] != rm_define.chassis_sdk_free_mode:
            self.attr_dict["f_angle"] = degree
            duss_result = self.chassis.set_follow_speed(0, 0, degree)
        else:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.warn(
                "CHASSIS_CTRL: cur mode is chassis_sdk_free_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "action_immediate",
                (degree),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )
        return get_task_dict(None, "action_immediate", (degree), result=duss_result)

    @event_register
    def move_with_degree(self, degree, time):
        logger.info("CHASSIS_CTRL: NOT SUPPORT NOW")
        duss_result = rm_define.DUSS_SUCCESS
        return get_task_dict(
            None, "action_immediate", (degree, time), result=duss_result
        )

    ## python and scratch ##
    def get_wheel_speed(self, wheel_num=None):
        logger.info("CHASSIS_CTRL: get chassis %s wheel speed" % wheel_num)
        if wheel_num == rm_define.chassis_wheel_1:
            return self.status_dict["cur_speed_wheel1"]
        elif wheel_num == rm_define.chassis_wheel_2:
            return self.status_dict["cur_speed_wheel2"]
        elif wheel_num == rm_define.chassis_wheel_3:
            return self.status_dict["cur_speed_wheel3"]
        elif wheel_num == rm_define.chassis_wheel_4:
            return self.status_dict["cur_speed_wheel4"]
        elif wheel_num == None:
            return (
                -self.status_dict["cur_speed_wheel2"],
                self.status_dict["cur_speed_wheel1"],
                -self.status_dict["cur_speed_wheel3"],
                self.status_dict["cur_speed_wheel4"],
            )
        else:
            logger.error("CHASSIS_CTRL: error wheel_num param in get_wheel_speed")
            return 0

    def get_speed(self, direction):
        logger.info("CHASSIS_CTRL: get chassis %s speed" % direction)
        if direction == rm_define.chassis_forward:
            return self.status_dict["cur_speed_bx"]
        elif direction == rm_define.chassis_translation:
            return self.status_dict["cur_speed_by"]
        elif direction == rm_define.chassis_rotate:
            return self.status_dict["cur_speed_wz"]
        else:
            logger.error("CHASSIS_CTRL, error direction param in get_speed")
            return 0

    def get_position_based_power_on(self, direction=None):
        logger.info(
            "CHASSIS_CTRL: get chassis %s position base power on" % str(direction)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.chassis_forward": rm_define.chassis_forward,
                "rm_define.chassis_translation": rm_define.chassis_translation,
                "rm_define.chassis_rotate": rm_define.chassis_rotate,
                "None": None,
            }
        )
        if direction == rm_define.chassis_forward:
            return (
                self.status_dict["cur_position_x"] - self.status_dict["init_position_x"]
            )
        elif direction == rm_define.chassis_translation:
            return (
                self.status_dict["cur_position_y"] - self.status_dict["init_position_y"]
            )
        elif direction == rm_define.chassis_rotate:
            if (
                abs(
                    self.status_dict["cur_attitude_yaw"]
                    - self.status_dict["init_attitude_yaw"]
                )
                <= 180
            ):
                return (
                    self.status_dict["cur_attitude_yaw"]
                    - self.status_dict["init_attitude_yaw"]
                )
            elif (
                self.status_dict["cur_attitude_yaw"]
                - self.status_dict["init_attitude_yaw"]
                > 180
            ):
                return (
                    self.status_dict["cur_attitude_yaw"]
                    - self.status_dict["init_attitude_yaw"]
                    - 360
                )
            elif (
                self.status_dict["cur_attitude_yaw"]
                - self.status_dict["init_attitude_yaw"]
                < -180
            ):
                return (
                    self.status_dict["cur_attitude_yaw"]
                    - self.status_dict["init_attitude_yaw"]
                    + 360
                )
        elif direction == None:
            return (
                self.status_dict["cur_position_x"]
                - self.status_dict["init_position_x"],
                self.status_dict["cur_position_y"]
                - self.status_dict["init_position_y"],
                self.status_dict["cur_attitude_yaw"]
                - self.status_dict["init_attitude_yaw"],
            )
        else:
            logger.error("CHASSIS_CTRL, error direction param in get_position")
            return -1

    ## python and scratch ##
    def get_attitude(self, attitude=None):
        logger.info("CHASSIS_CTRL: get chassis %s attitude" % str(attitude))
        CHECK_VALUE_IN_ENUM_LIST(
            attitude,
            **{
                "rm_define.chassis_pitch": rm_define.chassis_pitch,
                "rm_define.chassis_roll": rm_define.chassis_roll,
                "rm_define.chassis_yaw": rm_define.chassis_yaw,
                "None": None,
            }
        )
        if attitude == rm_define.chassis_pitch:
            return self.status_dict["cur_attitude_pitch"]
        elif attitude == rm_define.chassis_roll:
            return self.status_dict["cur_attitude_roll"]
        elif attitude == rm_define.chassis_yaw:
            return self.status_dict["cur_attitude_yaw"]
        elif attitude == None:
            return (
                self.status_dict["cur_attitude_pitch"],
                self.status_dict["cur_attitude_roll"],
                self.status_dict["cur_attitude_yaw"],
            )
        else:
            logger.error("CHASSIS_CTRL, error attitude param in get_attitude")
            return 0

    def is_static(self):
        return self.attitude_status_dict["static_flag"]

    def is_uphill(self):
        return self.attitude_status_dict["uphill_flag"]

    def is_downhill(self):
        return self.attitude_status_dict["downhill_flag"]

    def is_on_slope(self):
        return self.attitude_status_dict["on_slope_flag"]

    def is_pick_up(self):
        return self.attitude_status_dict["pick_up_flag"]

    def is_slip(self):
        return self.attitude_status_dict["slip_flag"]

    def is_impact(self):
        if time.time() - self.attitude_status_dict["impact_time"] < 1.0:
            return True
        else:
            return False

    def is_bumpy(self):
        return self.attitude_status_dict["impact_z_flag"]

    def is_roll_over(self):
        return self.attitude_status_dict["roll_over_flag"]

    def register_event(self, func_dict):
        if ChassisCtrl.CHASSIS_IMPACT_DETECTION_STR in func_dict.keys():
            logger.info(
                "CHASSIS_CTRL: register callback %s"
                % ChassisCtrl.CHASSIS_IMPACT_DETECTION_STR
            )
            event_str = ChassisCtrl.CHASSIS_IMPACT_DETECTION_STR
            self.event_client.event_callback_register(event_str, func_dict[event_str])
            self.recognition_event_cb.append(event_str)

    def impact_detection_process(self):
        if (
            self.attitude_status_dict["impact_x_flag"]
            or self.attitude_status_dict["impact_y_flag"]
        ):
            if ChassisCtrl.CHASSIS_IMPACT_DETECTION_STR in self.recognition_event_cb:
                self.event_client.event_come_to_process(
                    ChassisCtrl.CHASSIS_IMPACT_DETECTION_STR
                )

    def attitute_status_process(self, event_client, msg):
        data = msg["data"]
        status = tools.byte_to_uint32(data)

        self.attitude_status_dict["static_flag"] = (status >> 0) & 0x01
        self.attitude_status_dict["uphill_flag"] = (status >> 1) & 0x01
        self.attitude_status_dict["downhill_flag"] = (status >> 2) & 0x01
        self.attitude_status_dict["on_slope_flag"] = (status >> 3) & 0x01
        self.attitude_status_dict["pick_up_flag"] = (status >> 4) & 0x01
        self.attitude_status_dict["slip_flag"] = (status >> 5) & 0x01
        self.attitude_status_dict["impact_x_flag"] = (status >> 6) & 0x01
        self.attitude_status_dict["impact_y_flag"] = (status >> 7) & 0x01
        self.attitude_status_dict["impact_z_flag"] = (status >> 8) & 0x01
        self.attitude_status_dict["roll_over"] = (status >> 9) & 0x01
        self.attitude_status_dict["hill_static"] = (status >> 10) & 0x01

        if (
            self.attitude_status_dict["impact_x_flag"]
            or self.attitude_status_dict["impact_y_flag"]
        ):
            self.attitude_status_dict["impact_time"] = time.time()

        self.impact_detection_process()

    def sdk_attutude_info_push_enable(self):
        self.sdk_attutude_info_push_enable_flag = True

    def sdk_attutude_info_push_disable(self):
        self.sdk_attutude_info_push_enable_flag = False

    def sdk_info_push_attr_set(
        self,
        position_flag=None,
        pfreq=None,
        attitude_flag=None,
        afreq=None,
        status_flag=None,
        sfreq=None,
        freq=None,
    ):
        result = False

        if position_flag == "on":
            result = True
            self.sdk_position_info_push_attr["enable_flag"] = True
            if pfreq:
                if pfreq in self.sdk_info_push_freq_list:
                    self.sdk_position_info_push_attr["freq_count"] = (
                        self.update_attitude_status_unit / pfreq
                    )
                    result = True
                else:
                    result = False
        elif position_flag == "off":
            self.sdk_position_info_push_attr["enable_flag"] = False
            self.sdk_position_info_push_attr["freq_count"] = (
                self.update_attitude_status_unit / self.sdk_info_push_freq_default
            )
            result = True

        if attitude_flag == "on":
            result = True
            self.sdk_attitude_info_push_attr["enable_flag"] = True
            if afreq:
                if afreq in self.sdk_info_push_freq_list:
                    self.sdk_attitude_info_push_attr["freq_count"] = (
                        self.update_attitude_status_unit / afreq
                    )
                    result = True
                else:
                    result = False
        elif attitude_flag == "off":
            self.sdk_attitude_info_push_attr["enable_flag"] = False
            self.sdk_attitude_info_push_attr["freq_count"] = (
                self.update_attitude_status_unit / self.sdk_info_push_freq_default
            )
            result = True

        if status_flag == "on":
            result = True
            self.sdk_status_info_push_attr["enable_flag"] = True
            if sfreq:
                if sfreq in self.sdk_info_push_freq_list:
                    self.sdk_status_info_push_attr["freq_count"] = (
                        self.update_attitude_status_unit / sfreq
                    )
                    result = True
                else:
                    result = False
        elif status_flag == "off":
            self.sdk_status_info_push_attr["enable_flag"] = False
            self.sdk_status_info_push_attr["freq_count"] = (
                self.update_attitude_status_unit / self.sdk_info_push_freq_default
            )
            result = True

        if freq:
            if freq in self.sdk_info_push_freq_list:
                self.sdk_position_info_push_attr["freq_count"] = (
                    self.update_attitude_status_unit / freq
                )
                self.sdk_attitude_info_push_attr["freq_count"] = (
                    self.update_attitude_status_unit / freq
                )
                self.sdk_status_info_push_attr["freq_count"] = (
                    self.update_attitude_status_unit / freq
                )
                result = True
            else:
                result = False

        return result

    def sdk_info_push_callback_register(self, callback):
        if callable(callback):
            self.sdk_info_push_callback = callback

    def update_attitude_status(self, status_dict):
        self.status_dict = status_dict
        self.update_stick_overlay_mode()
        info = {}
        self.update_attitude_status_count += 1
        if self.update_attitude_status_count >= self.sdk_info_push_freq_lcm:
            self.update_attitude_status_count = 0

        if self.sdk_info_push_callback:
            if (
                self.sdk_position_info_push_attr["enable_flag"]
                and self.update_attitude_status_count
                % int(self.sdk_position_info_push_attr["freq_count"])
                == 0
            ):
                info["position"] = self.get_position()[0:2]
            if (
                self.sdk_attitude_info_push_attr["enable_flag"]
                and self.update_attitude_status_count
                % int(self.sdk_attitude_info_push_attr["freq_count"])
                == 0
            ):
                info["attitude"] = self.get_attitude()
            if (
                self.sdk_status_info_push_attr["enable_flag"]
                and self.update_attitude_status_count
                % int(self.sdk_status_info_push_attr["freq_count"])
                == 0
            ):
                info["status"] = self.get_status()
            self.sdk_info_push_callback(info)

    def update_stick_overlay_mode(self):
        if (
            self.attr_dict["stick_overlay_enable"]
            == rm_define.stick_overlay_and_axes_enable
            and self.attr_dict["mode"] == rm_define.chassis_sdk_free_mode
        ):
            if self.status_dict["stick_flag"] == True:
                if self.attr_dict["stick_overlay_need_update_mode"] == True:
                    logger.info(
                        "CHASSIS_CTRL: enable stick overlay on free mode, and stick vaild, change mode"
                    )
                    if not (
                        self.check_action_state("rotate")
                        or self.check_action_state("move")
                        or self.check_action_state("move_rotate")
                    ):  # check if stop or not
                        self.attr_dict["stick_overlay_need_update_mode"] = False
                        self._set_chassis_speed(0, 0, 0)
            else:
                if self.attr_dict["stick_overlay_need_update_mode"] == False:
                    logger.info(
                        "CHASSIS_CTRL: enable stick overlay on free mode, and stick invaild, change mode %s "
                        % (str(self.attr_dict["stick_overlay_need_update_mode"]))
                    )
                    if not (
                        self.check_action_state("rotate")
                        or self.check_action_state("move")
                        or self.check_action_state("move_rotate")
                    ):  # check if stop or not
                        self.attr_dict["stick_overlay_need_update_mode"] = True
                        self._set_chassis_stop_depend_mode()

    def _move_ctrl(self, func_start, t=rm_define.time_forever):
        speed_x = 0
        speed_y = 0
        if self.attr_dict["m_dire"] == rm_define.chassis_front:
            speed_x = self.attr_dict["m_speed"]
        elif self.attr_dict["m_dire"] == rm_define.chassis_back:
            speed_x = -self.attr_dict["m_speed"]
        elif self.attr_dict["m_dire"] == rm_define.chassis_right:
            speed_y = self.attr_dict["m_speed"]
        elif self.attr_dict["m_dire"] == rm_define.chassis_left:
            speed_y = -self.attr_dict["m_speed"]
        elif self.attr_dict["m_dire"] == rm_define.chassis_left_front:
            speed_x = self.attr_dict["m_speed"] / 1.414
            speed_y = -self.attr_dict["m_speed"] / 1.414
        elif self.attr_dict["m_dire"] == rm_define.chassis_left_back:
            speed_x = -self.attr_dict["m_speed"] / 1.414
            speed_y = -self.attr_dict["m_speed"] / 1.414
        elif self.attr_dict["m_dire"] == rm_define.chassis_right_front:
            speed_x = self.attr_dict["m_speed"] / 1.414
            speed_y = self.attr_dict["m_speed"] / 1.414
        elif self.attr_dict["m_dire"] == rm_define.chassis_right_back:
            speed_x = -self.attr_dict["m_speed"] / 1.414
            speed_y = self.attr_dict["m_speed"] / 1.414
        elif self.attr_dict["m_dire"] == rm_define.chassis_customize_direction:
            speed = self.attr_dict["cus_speed"]
            degree = self.attr_dict["cus_degree"]
            speed_x = math.cos(degree * math.pi / 180.0) * speed
            speed_y = math.sin(degree * math.pi / 180.0) * speed
        else:
            pass

        duss_result = self._move(speed_x, speed_y, func_start, t)
        return duss_result

    def _move(self, speed_x, speed_y, func_start, time_sleep):
        duss_result = self._set_chassis_speed(speed_x, speed_y, 0)
        time_sleep = time_sleep - (time.time() - func_start)
        if time_sleep > 0:
            has_condition, has_event = self.robot_sleep_interruptable(
                time_sleep * 1000, self.stop
            )
            if not has_event:
                self.stop()
            else:
                duss_result = rm_define.DUSS_TASK_INTERRUPT
        return duss_result

    def _rotate_ctrl(self, func_start, t=rm_define.time_forever):
        speed_yaw = 0
        if self.attr_dict["r_dire"] == rm_define.clockwise:
            speed_yaw = self.attr_dict["r_speed"]
        elif self.attr_dict["r_dire"] == rm_define.anticlockwise:
            speed_yaw = -self.attr_dict["r_speed"]
        else:
            pass

        duss_result = self._turn(speed_yaw, func_start, t)
        return duss_result

    def _turn(self, speed_yaw, func_start, time_sleep):
        duss_result = self._set_chassis_speed(0, 0, speed_yaw)
        time_sleep = time_sleep - (time.time() - func_start)
        if time_sleep > 0:
            has_condition, has_event = self.robot_sleep_interruptable(
                time_sleep * 1000, self.stop
            )
            if not has_event:
                self.stop()
            else:
                duss_result = rm_define.DUSS_TASK_INTERRUPT
        return duss_result

    def _set_chassis_speed(self, speed_x, speed_y, speed_z, update_f_angle=False):
        speed_x = int(speed_x * 10) / 10
        speed_y = int(speed_y * 10) / 10
        if self.attr_dict["mode"] == rm_define.chassis_sdk_free_mode:
            duss_result = self.chassis.set_move_speed(speed_x, speed_y, speed_z)
        elif self.attr_dict["mode"] == rm_define.chassis_sdk_follow_mode:
            if update_f_angle:
                self.attr_dict["f_angle"] = speed_z
            speed_z = self.attr_dict["f_angle"]
            duss_result = self.chassis.set_follow_speed(speed_x, speed_y, speed_z)
        else:
            duss_result = rm_define.DUSS_ERR_FAILURE
        return duss_result

    def _set_chassis_stop(self):
        duss_result = self.chassis.set_wheel_speed(0, 0, 0, 0)
        return duss_result

    def _set_chassis_stop_depend_mode(self):
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["mode"] == rm_define.chassis_sdk_follow_mode:
            duss_result = self.chassis.set_follow_speed(0, 0, 0)
        elif self.attr_dict["mode"] == rm_define.chassis_sdk_free_mode:
            duss_result = self.chassis.set_wheel_speed(0, 0, 0, 0)
            self.attr_dict["stick_overlay_need_update_mode"] = True
        return duss_result

    @event_register
    def __update_position(self, x, y, yaw_angle, wait_for_complete=True, **kw):
        m_speed = rm_define.speed_base_default
        r_speed = rm_define.speed_yaw_default
        axis_mode = rm_define.axis_mode_default

        if "m_speed" in kw.keys():
            m_speed = kw["m_speed"]
        if "r_speed" in kw.keys():
            r_speed = kw["r_speed"]
        if "axis_mode" in kw.keys():
            axis_mode = kw["axis_mode"]

        CHECK_VALUE_RANGE_AND_TYPE(x, -5, 5, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(y, -5, 5, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(yaw_angle, -1800, 1800, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(m_speed, 0.1, 2.5, int, float)
        CHECK_VALUE_RANGE_AND_TYPE(r_speed, 10, 540, int, float)

        if wait_for_complete:
            self.set_action_state("move")

            self.interrupt_func_register(self.position_cmd_interrupt_callback)
            self.finished_func_register(self.position_cmd_finished_callback)

            duss_result, identify = self.chassis.set_position_cmd(
                0,
                axis_mode,
                int(x * 100),
                int(y * 100),
                int(yaw_angle * 10),
                int(m_speed * 100),
                int(r_speed * 10),
                rm_define.TASK,
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify,
                    "task",
                    (x, y, yaw_angle, wait_for_complete, kw),
                    result=duss_result,
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None,
                    "no_task",
                    (x, y, yaw_angle, wait_for_complete, kw),
                    result=duss_result,
                )
        else:
            duss_result, resp = self.chassis.set_position_cmd(
                0,
                axis_mode,
                int(x * 100),
                int(y * 100),
                int(yaw_angle * 10),
                int(m_speed * 100),
                int(r_speed * 10),
                rm_define.NO_TASK,
            )
            return get_task_dict(
                None,
                "action_immediate",
                (x, y, yaw_angle, wait_for_complete, kw),
                result=duss_result,
            )

    ########### Python API ###########
    def set_move_speed(
        self, x=None, y=None, z=None, w2=None, w1=None, w3=None, w4=None
    ):
        if x != None:
            self.attr_dict["sdk_x_speed"] = x
        if y != None:
            self.attr_dict["sdk_y_speed"] = y
        if z != None:
            self.attr_dict["sdk_z_speed"] = z

        if w1 != None:
            self.attr_dict["sdk_w1_speed"] = w1

        if w2 != None:
            self.attr_dict["sdk_w2_speed"] = w2

        if w3 != None:
            self.attr_dict["sdk_w3_speed"] = w3

        if w4 != None:
            self.attr_dict["sdk_w4_speed"] = w4

    def get_move_speed(self, flag="real"):
        if flag == "target":
            return (
                self.attr_dict["sdk_x_speed"],
                self.attr_dict["sdk_y_speed"],
                self.attr_dict["sdk_z_speed"],
                self.attr_dict["sdk_w2_speed"],
                self.attr_dict["sdk_w1_speed"],
                self.attr_dict["sdk_w3_speed"],
                self.attr_dict["sdk_w4_speed"],
            )
        elif flag == "real":
            return (
                self.status_dict["cur_speed_bx"],
                self.status_dict["cur_speed_by"],
                self.status_dict["cur_speed_wz"],
                -self.status_dict["cur_speed_wheel2"],
                self.status_dict["cur_speed_wheel1"],
                -self.status_dict["cur_speed_wheel3"],
                self.status_dict["cur_speed_wheel4"],
            )
        else:
            return False

    def update_wheel_speed(self, w2=None, w1=None, w3=None, w4=None):
        if w1 == None:
            w1 = self.attr_dict["sdk_w1_speed"]
        if w2 == None:
            w2 = self.attr_dict["sdk_w2_speed"]
        if w3 == None:
            w3 = self.attr_dict["sdk_w3_speed"]
        if w4 == None:
            w4 = self.attr_dict["sdk_w4_speed"]
        result = get_result(self.set_wheel_speed(w2, w1, w3, w4))
        return result

    def update_move_speed(self, x, y, rotate):
        if x == None:
            x = self.attr_dict["sdk_x_speed"]
        if y == None:
            y = self.attr_dict["sdk_y_speed"]
        if rotate == None:
            rotate = self.attr_dict["sdk_z_speed"]
        result = get_result(self.move_with_speed(x, y, rotate))
        return result

    def update_position(
        self,
        x=None,
        y=None,
        yaw_angle=None,
        m_speed=None,
        r_speed=None,
        axis_mode=rm_define.axis_mode_default,
        wait_for_complete=True,
    ):
        kw = {}
        if m_speed != None:
            kw["m_speed"] = m_speed
        if r_speed != None:
            kw["r_speed"] = r_speed
        if axis_mode != None:
            kw["axis_mode"] = axis_mode
        if x == None:
            x = 0
        if y == None:
            y = 0
        if yaw_angle == None:
            yaw_angle = 0

        result = get_result(
            self.__update_position(
                x, y, -yaw_angle, wait_for_complete=wait_for_complete, **kw
            )
        )
        return result

    def update_position_based_on_cur(
        self,
        x=None,
        y=None,
        yaw_angle=None,
        m_speed=None,
        r_speed=None,
        wait_for_complete=True,
    ):
        if wait_for_complete == None:
            wait_for_completet = True
        self.update_position(
            x, y, yaw_angle, m_speed, r_speed, 0, wait_for_complete=wait_for_complete
        )

    def update_position_based_on_origin(
        self,
        x=None,
        y=None,
        yaw_angle=None,
        m_speed=None,
        r_speed=None,
        wait_for_complete=True,
    ):
        if wait_for_complete == None:
            wait_for_completet = True
        self.update_position(
            x, y, yaw_angle, m_speed, r_speed, 1, wait_for_complete=wait_for_complete
        )

    def update_pwm_value(self, port_enum, percent):
        result = get_result(self.set_pwm_value(port_enum, percent))
        return result

    # USE SCRATCH API
    # def get_wheel_speed():

    def get_position(self):
        return self.get_position_based_power_on()

    # USE SCRATCH API
    # def get_attitude():

    def get_status(self):
        return (
            self.attitude_status_dict["static_flag"],
            self.attitude_status_dict["uphill_flag"],
            self.attitude_status_dict["downhill_flag"],
            self.attitude_status_dict["on_slope_flag"],
            self.attitude_status_dict["pick_up_flag"],
            self.attitude_status_dict["slip_flag"],
            self.attitude_status_dict["impact_x_flag"],
            self.attitude_status_dict["impact_y_flag"],
            self.attitude_status_dict["impact_z_flag"],
            self.attitude_status_dict["roll_over"],
            self.attitude_status_dict["hill_static"],
        )


class GunCtrl(RobotCtrlTool):
    def __init__(self, event_client, type="gel"):
        super().__init__(event_client)
        self.gun = rm_module.Gun(event_client)
        self.event_client = event_client
        self.gun_timer = None
        self.timer_count = 0
        self.count = 1
        self.__type = 0

        if type == "gel":
            self.__type = 0
        elif type == "ir":
            self.__type = 1

    def init(self):
        # self.set_led(0)
        pass

    @event_register
    def set_fire_count(self, count=1):
        # logger.info('GUN_CTRL: set count, count is %s' % (count))
        CHECK_VALUE_RANGE_AND_TYPE(count, 1, 8, int)
        duss_result = rm_define.DUSS_SUCCESS
        count = tools.data_limit(
            count, rm_define.min_fire_count, rm_define.max_fire_count
        )
        self.count = count
        return get_task_dict(None, "attr_set", (count), result=duss_result)

    def get_fire_count(self):
        return self.count

    def _fire_timer_func(self, *arg, **kw):
        if self.timer_count == 0:
            self.gun.set_cmd_fire(self.__type, self.count)
        self.timer_count += 1
        self.timer_count %= 10  # 1s

    def _start_fire_timer(self):
        if self.gun_timer == None:
            self.timer_count = 0
            self.gun_timer = tools.get_timer(0.1, self._fire_timer_func)
            self.gun_timer.start()
        else:
            self.gun_timer.start()

    def _stop_fire_timer(self):
        if self.gun_timer and self.gun_timer.is_start():
            self.gun_timer.join()
            self.gun_timer.stop()

    def _destory_fire_timer(self):
        if self.gun_timer and self.gun_timer.is_alive():
            self.gun_timer.join()
            self.gun_timer.destory()

    @event_register
    def fire_once(self):
        logger.info("GUN_CTRL: fire once")
        duss_result = rm_define.DUSS_SUCCESS
        duss_result = self.gun.set_cmd_fire(self.__type, self.count)
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    @event_register
    def fire_continuous(self):
        self._start_fire_timer()
        # logger.info('GUN_CTRL: fire continue')
        duss_result = rm_define.DUSS_SUCCESS
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    @event_register
    def set_led(self, mode):
        logger.info("GUN_CTRL: set led, mode is %s" % (mode))
        duss_result = rm_define.DUSS_SUCCESS
        duss_result = self.gun.set_led(mode, 7)
        return get_task_dict(None, "action_immediate", (mode), result=duss_result)

    @event_register
    def stop(self):
        logger.info("GUN_CTRL: stop")
        self.set_led(0)
        self._stop_fire_timer()
        duss_result = rm_define.DUSS_SUCCESS
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def exit(self):
        logger.info("GUN_CTRL: exit")
        self._destory_fire_timer()


class GimbalCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.gimbal = rm_module.Gimbal(event_client)
        self.event_client = event_client
        self.attr_dict = {
            "mode": rm_define.gimbal_free_mode,
            "suspend_state": False,
            "stick_overlay_enable": rm_define.stick_overlay_disable,
            "pitch_acc": 30,
            "yaw_acc": 30,
            "pitch_cur": 0,
            "yaw_cur": 0,
            "dire": rm_define.gimbal_up,
            "sdk_pitch_acc": 0,
            "sdk_yaw_acc": 0,
        }
        self.status_dict = {
            "pitch_cur": 0,
            "yaw_cur": 0,
        }
        self.set_actions(["rotate", "rotate_with_degree", "rotate_with_speed"])
        self.set_mutex_action("rotate", ["rotate_with_degree", "rotate_with_speed"])
        self.set_mutex_action("rotate_with_degree", ["rotate", "rotate_with_speed"])
        self.set_mutex_action("rotate_with_speed", ["rotate", "rotate_with_degree"])

        self.update_attitude_status_count = 0
        self.update_attitude_status_unit = RobotCtrl.GIMBAL_CHASSIS_SUB_INFO_PUSH_FREQ
        self.sdk_info_push_callback = None
        self.sdk_info_push_freq_default = 5
        self.sdk_info_push_freq_list = [1, 5, 10, 20, 30, 50]
        self.sdk_attitude_info_push_attr = {
            "enable_flag": False,
            "freq_count": self.sdk_info_push_freq_default,
        }

        # calc freq LCM (lowest common multiple)
        self.sdk_info_push_freq_lcm = 1
        for i in self.sdk_info_push_freq_list:
            self.sdk_info_push_freq_lcm = int(
                i
                * self.sdk_info_push_freq_lcm
                / math.gcd(i, self.sdk_info_push_freq_lcm)
            )
        self.sdk_info_push_freq_lcm

    @event_register
    def set_work_mode(self, mode):
        logger.info("GIMBAL_CTRL: set work mode, mode is %s" % (mode))
        duss_result = rm_define.DUSS_SUCCESS
        duss_result = self.gimbal.set_work_mode(mode)
        if duss_result == rm_define.DUSS_SUCCESS:
            self.attr_dict["mode"] = mode
        return get_task_dict(None, "attr_set", (mode), result=duss_result)

    def set_mode_attr(self, mode):
        self.attr_dict["mode"] = mode

    @event_register
    def enable_stick_overlay(self):
        logger.info("GIMBAL_CTRL: enable stick overlay")
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["stick_overlay_enable"] != rm_define.stick_overlay_enable:
            self.attr_dict["stick_overlay_enable"] = rm_define.stick_overlay_enable
            duss_result = self.gimbal.set_stick_overlay(rm_define.stick_overlay_enable)
            if self.check_action_state("rotate"):
                _, _, _, duss_result = self.rotate(self.attr_dict["dire"])

        return get_task_dict(None, "attr_set", (), result=duss_result)

    @event_register
    def disable_stick_overlay(self):
        logger.info("GIMBAL_CTRL: disable stick overlay")
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["stick_overlay_enable"] != rm_define.stick_overlay_disable:
            self.attr_dict["stick_overlay_enable"] = rm_define.stick_overlay_disable
            duss_result = self.gimbal.set_stick_overlay(rm_define.stick_overlay_disable)
            if self.check_action_state("rotate"):
                _, _, _, duss_result = self.rotate(self.attr_dict["dire"])

        return get_task_dict(None, "attr_set", (), result=duss_result)

    @event_register
    def suspend(self):
        self.attr_dict["suspend_state"] = True
        duss_result = self.gimbal.set_suspend_resume(rm_define.gimbal_suspend)
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    @event_register
    def resume(self):
        self.attr_dict["suspend_state"] = False
        duss_result = self.gimbal.set_suspend_resume(rm_define.gimbal_resume)
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    @event_register
    def set_rotate_speed(self, speed, speed2=None):
        logger.info("GIMBAL_CTRL: set rotate speed, speed is %f" % speed)
        CHECK_VALUE_RANGE_AND_TYPE(
            speed, 0, rm_define.gimbal_rotate_speed_max, int, float
        )
        duss_result = rm_define.DUSS_SUCCESS
        speed = tools.data_limit(
            speed, rm_define.gimbal_rotate_speed_min, rm_define.gimbal_rotate_speed_max
        )
        self.attr_dict["pitch_acc"] = speed
        if speed2 == None:
            self.attr_dict["yaw_acc"] = speed
        else:
            self.attr_dict["yaw_acc"] = speed2
        if self.check_action_state("rotate"):
            _, _, _, duss_result = self.rotate(self.attr_dict["dire"])
        elif self.check_action_state("rotate_with_speed"):
            _, _, _, duss_result = self.rotate_with_speed(
                self.attr_dict["yaw_acc"], self.attr_dict["pitch_acc"]
            )
        return get_task_dict(None, "attr_set", (speed), result=duss_result)

    ## python and scratch ##
    def get_axis_angle(self, axis=None):
        logger.info("GIMBAL_CTRL: get degree, axis is %s" % str(axis))
        CHECK_VALUE_IN_ENUM_LIST(
            axis,
            **{
                "rm_define.gimbal_axis_pitch": rm_define.gimbal_axis_pitch,
                "rm_define.gimbal_axis_yaw": rm_define.gimbal_axis_yaw,
                "None": None,
            }
        )
        if axis == rm_define.gimbal_axis_pitch:
            return self.status_dict["pitch_cur"]
        elif axis == rm_define.gimbal_axis_yaw:
            return self.status_dict["yaw_cur"]
        elif axis == None:
            return (self.status_dict["pitch_cur"], self.status_dict["yaw_cur"])

    def _stop_rotate(self):
        duss_result = rm_define.DUSS_SUCCESS
        if self.check_action_state("rotate") or self.check_action_state(
            "rotate_with_speed"
        ):
            duss_result = self.gimbal.set_accel_ctrl(0, 0, 0x00)
            if duss_result == rm_define.DUSS_SUCCESS:
                self.reset_action_state("rotate")
                self.reset_action_state("rotate_with_speed")
        return duss_result

    @event_register
    def rotate(self, direction=None):
        logger.info("GIMBAL_CTRL: rotating, direction is  %s" % direction)
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.gimbal_up": rm_define.gimbal_up,
                "rm_define.gimbal_down": rm_define.gimbal_down,
                "rm_define.gimbal_right": rm_define.gimbal_right,
                "rm_define.gimbal_left": rm_define.gimbal_left,
                "None": None,
            }
        )
        duss_result = rm_define.DUSS_SUCCESS
        pitch_accel = 0
        yaw_accel = 0
        self.attr_dict["dire"] = direction
        if direction == rm_define.gimbal_up:
            pitch_accel = self.attr_dict["pitch_acc"]
        elif direction == rm_define.gimbal_down:
            pitch_accel = -self.attr_dict["pitch_acc"]
        elif direction == rm_define.gimbal_right:
            yaw_accel = self.attr_dict["yaw_acc"]
        elif direction == rm_define.gimbal_left:
            yaw_accel = -self.attr_dict["yaw_acc"]
        else:
            pitch_accel = self.attr_dict["pitch_acc"]
            yaw_accel = self.attr_dict["yaw_acc"]
        self.set_action_state("rotate")

        err_code = None
        if (
            self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode
            and yaw_accel != 0
        ):
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the yaw ctrl is invalid"
            )
            yaw_accel = 0
            err_code = rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT

        duss_result = self.gimbal.set_accel_ctrl(pitch_accel, yaw_accel)
        if err_code:
            return get_task_dict(
                None,
                "action_immediate",
                (direction),
                result=duss_result,
                err_code=err_code,
            )
        else:
            return get_task_dict(
                None, "action_immediate", (direction), result=duss_result
            )

    @event_register
    def rotate_with_speed(self, yaw_speed, pitch_speed):
        CHECK_VALUE_RANGE_AND_TYPE(
            yaw_speed,
            -rm_define.gimbal_rotate_speed_max,
            rm_define.gimbal_rotate_speed_max,
            int,
            float,
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            pitch_speed,
            -rm_define.gimbal_rotate_speed_max,
            rm_define.gimbal_rotate_speed_max,
            int,
            float,
        )
        err_code = None
        if (
            self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode
            and yaw_speed != 0
        ):
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the yaw ctrl is invalid"
            )
            yaw_speed = 0
            err_code = rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT
        self.set_action_state("rotate_with_speed")
        duss_result = self.gimbal.set_accel_ctrl(pitch_speed, yaw_speed)

        if err_code:
            return get_task_dict(
                None,
                "action_immediate",
                (yaw_speed, pitch_speed),
                result=duss_result,
                err_code=err_code,
            )
        else:
            return get_task_dict(
                None, "action_immediate", (yaw_speed, pitch_speed), result=duss_result
            )

    @event_register
    def rotate_with_degree(self, direction, degree):
        logger.info(
            "GIMBAL_CTRL: rotate with degree, direction is  %s, degree is %f"
            % (direction, degree)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            direction,
            **{
                "rm_define.gimbal_up": rm_define.gimbal_up,
                "rm_define.gimbal_down": rm_define.gimbal_down,
                "rm_define.gimbal_right": rm_define.gimbal_right,
                "rm_define.gimbal_left": rm_define.gimbal_left,
            }
        )
        if direction == rm_define.gimbal_down or direction == rm_define.gimbal_up:
            CHECK_VALUE_RANGE_AND_TYPE(
                degree,
                rm_define.gimbal_pitch_degree_ctrl_min,
                rm_define.gimbal_pitch_degree_ctrl_max,
                int,
                float,
            )
        elif direction == rm_define.gimbal_left or direction == rm_define.gimbal_right:
            CHECK_VALUE_RANGE_AND_TYPE(
                degree,
                rm_define.gimbal_yaw_degree_ctrl_min,
                rm_define.gimbal_yaw_degree_ctrl_max,
                int,
                float,
            )
        duss_result = rm_define.DUSS_SUCCESS
        self._stop_rotate()
        pitch_degree = 0
        yaw_degree = 0
        axis_maskbit = 0
        if direction == rm_define.gimbal_down:
            degree = (
                tools.data_limit(
                    degree,
                    rm_define.gimbal_pitch_degree_ctrl_min,
                    rm_define.gimbal_pitch_degree_ctrl_max,
                )
                * 10
            )
            pitch_degree = -degree
            axis_maskbit = rm_define.gimbal_axis_pitch_maskbit
        elif direction == rm_define.gimbal_up:
            degree = (
                tools.data_limit(
                    degree,
                    rm_define.gimbal_pitch_degree_ctrl_min,
                    rm_define.gimbal_pitch_degree_ctrl_max,
                )
                * 10
            )
            pitch_degree = degree
            axis_maskbit = rm_define.gimbal_axis_pitch_maskbit
        elif direction == rm_define.gimbal_right:
            degree = (
                tools.data_limit(
                    degree,
                    rm_define.gimbal_yaw_degree_ctrl_min,
                    rm_define.gimbal_yaw_degree_ctrl_max,
                )
                * 10
            )
            yaw_degree = degree
            axis_maskbit = rm_define.gimbal_axis_yaw_maskbit
        elif direction == rm_define.gimbal_left:
            degree = (
                tools.data_limit(
                    degree,
                    rm_define.gimbal_yaw_degree_ctrl_min,
                    rm_define.gimbal_yaw_degree_ctrl_max,
                )
                * 10
            )
            yaw_degree = -degree
            axis_maskbit = rm_define.gimbal_axis_yaw_maskbit

        self.set_action_state("rotate_with_degree")
        self.interrupt_func_register(self.rotate_with_degree_stop)
        self.finished_func_register(self.reset_action_state, "rotate_with_degree")

        if self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode and (
            axis_maskbit & rm_define.gimbal_axis_yaw_maskbit == 1
        ):
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "no_task",
                (direction, degree),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )

        if self.attr_dict["pitch_acc"] == self.attr_dict["yaw_acc"] == 0:
            return get_task_dict(
                None, "no_task", (direction, degree), result=duss_result
            )

        duss_result, identify = self.gimbal.set_degree_ctrl(
            pitch_degree,
            yaw_degree,
            self.attr_dict["pitch_acc"],
            self.attr_dict["yaw_acc"],
            axis_maskbit,
            rm_define.gimbal_coodrdinate_cur,
            cmd_type=rm_define.TASK,
        )

        if duss_result == rm_define.DUSS_SUCCESS:
            return get_task_dict(
                identify, "task", (direction, degree), result=duss_result
            )
        else:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (direction, degree), result=duss_result
            )

    @event_register
    def pitch_ctrl(self, degree, coordinate=rm_define.gimbal_coodrdinate_ned):
        logger.info("GIMBAL_CTRL: pitch ctrl, degree is %f" % (degree))
        CHECK_VALUE_RANGE_AND_TYPE(
            degree,
            rm_define.gimbal_pitch_degree_min,
            rm_define.gimbal_pitch_degree_max,
            int,
            float,
        )
        duss_result = rm_define.DUSS_SUCCESS
        self._stop_rotate()
        degree = (
            tools.data_limit(
                degree,
                rm_define.gimbal_pitch_degree_min,
                rm_define.gimbal_pitch_degree_max,
            )
            * 10
        )

        self.set_action_state("rotate_with_degree")
        self.interrupt_func_register(self.rotate_with_degree_stop)
        self.finished_func_register(self.reset_action_state, "rotate_with_degree")

        if self.attr_dict["pitch_acc"] == 0:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (degree, coordinate), result=duss_result
            )

        duss_result, identify = self.gimbal.set_degree_ctrl(
            degree,
            0,
            self.attr_dict["pitch_acc"],
            self.attr_dict["yaw_acc"],
            rm_define.gimbal_axis_pitch_maskbit,
            coordinate,
            cmd_type=rm_define.TASK,
        )

        if duss_result == rm_define.DUSS_SUCCESS:
            return get_task_dict(
                identify, "task", (degree, coordinate), result=duss_result
            )
        else:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (degree, coordinate), result=duss_result
            )

    @event_register
    def yaw_ctrl(
        self, degree, coordinate=rm_define.gimbal_coodrdinate_car, follow_flag=False
    ):
        logger.info("GIMBAL_CTRL: yaw ctrl, degree is %f" % (degree))
        if coordinate == rm_define.gimbal_coodrdinate_car:
            CHECK_VALUE_RANGE_AND_TYPE(
                degree,
                rm_define.gimbal_yaw_degree_min,
                rm_define.gimbal_yaw_degree_max,
                int,
                float,
            )
        else:
            CHECK_VALUE_RANGE_AND_TYPE(
                degree,
                rm_define.gimbal_yaw_degree_ctrl_min,
                rm_define.gimbal_yaw_degree_ctrl_max,
                int,
                float,
            )
        duss_result = rm_define.DUSS_SUCCESS
        # TODO:follow_flag is not a good ideal to process follow_mode yaw ctrl, and will fix next version(depend on gimbal protocol)
        if follow_flag or self.attr_dict["mode"] != rm_define.gimbal_yaw_follow_mode:
            self._stop_rotate()
            degree = (
                tools.data_limit(
                    degree,
                    rm_define.gimbal_yaw_degree_min,
                    rm_define.gimbal_yaw_degree_max,
                )
                * 10
            )

            self.set_action_state("rotate_with_degree")
            self.interrupt_func_register(self.rotate_with_degree_stop)
            self.finished_func_register(self.reset_action_state, "rotate_with_degree")

            if self.attr_dict["yaw_acc"] == 0:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (degree, coordinate), result=duss_result
                )

            duss_result, identify = self.gimbal.set_degree_ctrl(
                0,
                degree,
                self.attr_dict["pitch_acc"],
                self.attr_dict["yaw_acc"],
                rm_define.gimbal_axis_yaw_maskbit,
                coordinate,
                cmd_type=rm_define.TASK,
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (degree, coordinate), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
        else:
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "no_task",
                (degree, coordinate),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )

        return get_task_dict(None, "no_task", (degree, coordinate), result=duss_result)

    @event_register
    def angle_ctrl(self, yaw, pitch, coordinate=rm_define.gimbal_coodrdinate_4):
        logger.info("GIMBAL_CTRL: angle ctrl, pitch is %f, yaw is %f" % (pitch, yaw))
        CHECK_VALUE_RANGE_AND_TYPE(
            yaw,
            rm_define.gimbal_yaw_degree_min,
            rm_define.gimbal_yaw_degree_ctrl_max,
            int,
            float,
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            pitch,
            rm_define.gimbal_pitch_degree_min,
            rm_define.gimbal_pitch_degree_ctrl_max,
            int,
            float,
        )
        duss_result = rm_define.DUSS_SUCCESS
        axis_maskbit = rm_define.gimbal_axis_pitch_yaw_maskbit
        err_code = None
        if self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode:
            axis_maskbit = axis_maskbit & ~rm_define.gimbal_axis_yaw_maskbit
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the yaw ctrl invalid"
            )
            err_code = rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT

        self._stop_rotate()
        pitch = (
            tools.data_limit(
                pitch,
                rm_define.gimbal_pitch_degree_ctrl_min,
                rm_define.gimbal_pitch_degree_ctrl_max,
            )
            * 10
        )
        yaw = (
            tools.data_limit(
                yaw,
                rm_define.gimbal_yaw_degree_ctrl_min,
                rm_define.gimbal_yaw_degree_ctrl_max,
            )
            * 10
        )

        self.set_action_state("rotate_with_degree")
        self.interrupt_func_register(self.rotate_with_degree_stop)
        self.finished_func_register(self.reset_action_state, "rotate_with_degree")

        if self.attr_dict["pitch_acc"] == self.attr_dict["yaw_acc"] == 0:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (yaw, pitch, coordinate), result=duss_result
            )

        duss_result, identify = self.gimbal.set_degree_ctrl(
            pitch,
            yaw,
            self.attr_dict["pitch_acc"],
            self.attr_dict["yaw_acc"],
            axis_maskbit,
            coordinate,
            cmd_type=rm_define.TASK,
        )

        if duss_result == rm_define.DUSS_SUCCESS:
            if err_code:
                return get_task_dict(
                    identify,
                    "task",
                    (yaw, pitch, coordinate),
                    result=duss_result,
                    err_code=err_code,
                )
            else:
                return get_task_dict(
                    identify, "task", (yaw, pitch, coordinate), result=duss_result
                )
        else:
            self.interrupt_func_unregister()
            if err_code:
                return get_task_dict(
                    None,
                    "no_task",
                    (yaw, pitch, coordinate),
                    result=duss_result,
                    err_code=err_code,
                )
            else:
                return get_task_dict(
                    None, "no_task", (yaw, pitch, coordinate), result=duss_result
                )

    @event_register
    def __angle_ctrl(self, yaw=None, pitch=None, wait_for_complete=True, **kw):
        pitch_speed = self.attr_dict["pitch_acc"]
        yaw_speed = self.attr_dict["yaw_acc"]
        coordinate = rm_define.gimbal_coodrdinate_4

        if "coordinate" in kw.keys():
            coordinate = kw["coordinate"]
        if "pitch_speed" in kw.keys():
            pitch_speed = kw["pitch_speed"]
        if "yaw_speed" in kw.keys():
            yaw_speed = kw["yaw_speed"]

        logger.info(
            "GIMBAL_CTRL: angle ctrl, pitch is %s, yaw is %s" % (str(pitch), (yaw))
        )

        duss_result = rm_define.DUSS_SUCCESS

        axis_maskbit = rm_define.gimbal_axis_pitch_yaw_maskbit

        if yaw == None and pitch == None:
            return get_task_dict(
                None,
                "action_immediate",
                (yaw, pitch, wait_for_complete, kw),
                result=duss_result,
            )

        if yaw == None:
            axis_maskbit = rm_define.gimbal_axis_pitch_maskbit
            yaw = 0

        if pitch == None:
            axis_maskbit = rm_define.gimbal_axis_yaw_maskbit
            pitch = 0

        if coordinate == rm_define.gimbal_coodrdinate_cur:
            CHECK_VALUE_RANGE_AND_TYPE(
                yaw,
                rm_define.gimbal_yaw_degree_ctrl_min,
                rm_define.gimbal_yaw_degree_ctrl_max,
                int,
                float,
            )
            CHECK_VALUE_RANGE_AND_TYPE(
                pitch,
                rm_define.gimbal_pitch_degree_ctrl_min,
                rm_define.gimbal_pitch_degree_ctrl_max,
                int,
                float,
            )
        else:
            CHECK_VALUE_RANGE_AND_TYPE(
                yaw,
                rm_define.gimbal_yaw_degree_min,
                rm_define.gimbal_yaw_degree_max,
                int,
                float,
            )
            CHECK_VALUE_RANGE_AND_TYPE(
                pitch,
                rm_define.gimbal_pitch_degree_min,
                rm_define.gimbal_pitch_degree_max,
                int,
                float,
            )
        CHECK_VALUE_RANGE_AND_TYPE(
            pitch_speed,
            -rm_define.gimbal_rotate_speed_max,
            rm_define.gimbal_rotate_speed_max,
            int,
            float,
        )
        CHECK_VALUE_RANGE_AND_TYPE(
            yaw_speed,
            -rm_define.gimbal_rotate_speed_max,
            rm_define.gimbal_rotate_speed_max,
            int,
            float,
        )

        err_code = None
        if self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode:
            axis_maskbit = axis_maskbit & ~rm_define.gimbal_axis_yaw_maskbit
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the yaw ctrl invalid"
            )
            err_code = rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT

        self._stop_rotate()
        pitch = (
            tools.data_limit(
                pitch,
                rm_define.gimbal_pitch_degree_ctrl_min,
                rm_define.gimbal_pitch_degree_ctrl_max,
            )
            * 10
        )
        yaw = (
            tools.data_limit(
                yaw,
                rm_define.gimbal_yaw_degree_ctrl_min,
                rm_define.gimbal_yaw_degree_ctrl_max,
            )
            * 10
        )

        if wait_for_complete:
            self.set_action_state("rotate_with_degree")
            self.interrupt_func_register(self.rotate_with_degree_stop)
            self.finished_func_register(self.reset_action_state, "rotate_with_degree")

            if self.attr_dict["pitch_acc"] == self.attr_dict["yaw_acc"] == 0:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None,
                    "no_task",
                    (yaw, pitch, coordinate, wait_for_complete),
                    result=duss_result,
                )

            duss_result, identify = self.gimbal.set_degree_ctrl(
                pitch,
                yaw,
                pitch_speed,
                yaw_speed,
                axis_maskbit,
                coordinate,
                cmd_type=rm_define.TASK,
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                if err_code:
                    return get_task_dict(
                        identify,
                        "task",
                        (yaw, pitch, wait_for_complete, kw),
                        result=duss_result,
                        err_code=err_code,
                    )
                else:
                    return get_task_dict(
                        identify,
                        "task",
                        (yaw, pitch, wait_for_complete, kw),
                        result=duss_result,
                    )
            else:
                self.interrupt_func_unregister()
                if err_code:
                    return get_task_dict(
                        None,
                        "no_task",
                        (yaw, pitch, wait_for_complete, kw),
                        result=duss_result,
                        err_code=err_code,
                    )
                else:
                    return get_task_dict(
                        None,
                        "no_task",
                        (yaw, pitch, wait_for_complete, kw),
                        result=duss_result,
                    )
        else:
            duss_result, resp = self.gimbal.set_degree_ctrl(
                pitch,
                yaw,
                pitch_speed,
                yaw_speed,
                axis_maskbit,
                coordinate,
                cmd_type=rm_define.NO_TASK,
            )
            return get_task_dict(
                None,
                "action_immediate",
                (yaw, pitch, wait_for_complete, kw),
                result=duss_result,
            )

    @event_register
    def __recenter(self, pitch_accel=-1, yaw_accel=-1):
        logger.info("GIMBAL_CTRL: return middle")
        duss_result = rm_define.DUSS_SUCCESS
        axis_maskbit = rm_define.gimbal_axis_pitch_yaw_maskbit
        if self.attr_dict["mode"] == rm_define.gimbal_yaw_follow_mode:
            axis_maskbit = axis_maskbit & ~rm_define.gimbal_axis_yaw_maskbit
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_yaw_follow_mode, the yaw ctrl invalid"
            )
            return get_task_dict(
                None,
                "no_task",
                (pitch_accel, yaw_accel),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )
        self._stop_rotate()
        if pitch_accel == -1:
            pitch_accel = self.attr_dict["pitch_acc"]
        if yaw_accel == -1:
            yaw_accel = self.attr_dict["yaw_acc"]

        self.set_action_state("rotate_with_degree")
        self.interrupt_func_register(self.rotate_with_degree_stop)
        self.finished_func_register(self.reset_action_state, "rotate_with_degree")
        duss_result, identify = self.gimbal.return_middle(
            axis_maskbit, pitch_accel, 0, yaw_accel
        )

        if duss_result == rm_define.DUSS_SUCCESS:
            return get_task_dict(
                identify, "task", (pitch_accel, yaw_accel), result=duss_result
            )
        else:
            self.interrupt_func_unregister()
            return get_task_dict(
                None, "no_task", (pitch_accel, yaw_accel), result=duss_result
            )

    @event_register
    def set_follow_chassis_offset(self, degree):
        logger.info("GIMBAL_CTRL: set follow chassis offet, the degree is %f" % degree)
        CHECK_VALUE_RANGE_AND_TYPE(degree, -180, 180, int, float)
        duss_result = rm_define.DUSS_SUCCESS
        if self.attr_dict["mode"] != rm_define.gimbal_free_mode:
            duss_result = self.yaw_ctrl(degree, follow_flag=True)
        else:
            logger.warn(
                "GIMBAL_CTRL: cur mode is gimbal_free_mode, the function is invalid"
            )
            return get_task_dict(
                None,
                "action_immediate",
                (degree),
                result=duss_result,
                err_code=rm_define.BLOCK_ERR_TANKMODE_NOT_SUPPORT,
            )
        return get_task_dict(None, "action_immediate", (degree), result=duss_result)

    @event_register
    def compound_motion_ctrl(self, enable_flag, axis, cycle, margin, times=0):
        test_flag = 1
        phase = 0
        # times = 0
        if axis == rm_define.gimbal_axis_pitch:
            axis = 1
        elif axis == rm_define.gimbal_axis_yaw:
            axis = 2
        else:
            return
        cycle = cycle * 1000  # to ms
        margin = margin * 10
        margin = tools.data_limit(
            margin,
            rm_define.gimbal_compound_motion_margin_min,
            rm_define.gimbal_compound_motion_margin_max,
        )

        duss_result = self.gimbal.set_compound_motion_ctrl(
            test_flag, enable_flag, axis, phase, cycle, margin, times
        )
        return get_task_dict(
            None,
            "action_immediate",
            (enable_flag, axis, cycle, margin, times),
            result=duss_result,
        )

    @event_register
    def compound_motion_stop(self):
        duss_result = self.gimbal.set_compound_motion_ctrl(0, 0, 0, 0, 0, 0, 0)
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def sdk_info_push_attr_set(self, attitude_flag=None, afreq=None, freq=None):
        if attitude_flag == "on":
            self.sdk_attitude_info_push_attr["enable_flag"] = True
            if afreq and afreq in self.sdk_info_push_freq_list:
                self.sdk_attitude_info_push_attr["freq_count"] = (
                    self.update_attitude_status_unit / afreq
                )
            return True
        elif attitude_flag == "off":
            self.sdk_attitude_info_push_attr["enable_flag"] = False
            self.sdk_attitude_info_push_attr["freq_count"] = (
                self.update_attitude_status_unit / self.sdk_info_push_freq_default
            )
            return True

        if freq and freq in self.sdk_info_push_freq_list:
            self.sdk_attitude_info_push_attr["freq_count"] = (
                self.update_attitude_status_unit / freq
            )

        return False

    def sdk_info_push_callback_register(self, callback):
        if callable(callback):
            self.sdk_info_push_callback = callback

    def update_attitude_status(self, status_dict):
        self.status_dict = status_dict
        info = {}
        self.update_attitude_status_count += 1
        if self.update_attitude_status_count >= self.sdk_info_push_freq_lcm:
            self.update_attitude_status_count = 0

        if self.sdk_info_push_callback:
            if (
                self.sdk_attitude_info_push_attr["enable_flag"]
                and self.update_attitude_status_count
                % int(self.sdk_attitude_info_push_attr["freq_count"])
                == 0
            ):
                info["attitude"] = self.get_angle()
            self.sdk_info_push_callback(info)

    def rotate_with_degree_stop(self):
        logger.info("GIMBAL_CTRL: rotate with degree stop")
        self.gimbal.set_degree_ctrl_stop()

    def recenter_stop(self):
        logger.info("GIMBAL_CTRL: return middle stop")
        self.gimbal.return_middle_stop()

    def stop(self):
        logger.info("GIMBAL_CTRL: stop")
        if self.attr_dict["suspend_state"] == True:
            return
        self.compound_motion_stop()
        self._stop_rotate()
        self.rotate_with_degree_stop()
        self.recenter_stop()

    def init(self):
        logger.info("GIMBAL_CTRL: init")
        # self.sub_attitude_info(self.attitude_info_push_process)

    def exit(self):
        pass
        # self.unsub_attitude_info()

    ## Python API ##
    def set_speed(self, speed=None, speed2=None):
        if speed != None:
            CHECK_VALUE_RANGE_AND_TYPE(
                speed,
                -rm_define.gimbal_rotate_speed_max,
                rm_define.gimbal_rotate_speed_max,
                int,
                float,
                None,
            )
            speed = tools.data_limit(
                speed,
                -rm_define.gimbal_rotate_speed_max,
                rm_define.gimbal_rotate_speed_max,
            )
            self.attr_dict["pitch_acc"] = speed
        if speed2 != None:
            CHECK_VALUE_RANGE_AND_TYPE(
                speed2,
                -rm_define.gimbal_rotate_speed_max,
                rm_define.gimbal_rotate_speed_max,
                int,
                float,
                None,
            )
            speed2 = tools.data_limit(
                speed2,
                -rm_define.gimbal_rotate_speed_max,
                rm_define.gimbal_rotate_speed_max,
            )
            self.attr_dict["yaw_acc"] = speed2
        return True

    def get_speed(self):
        return (self.attr_dict["pitch_acc"], self.attr_dict["yaw_acc"])

    def update_speed(self, pitch=None, yaw=None):
        if pitch == None:
            pitch = self.attr_dict["sdk_pitch_acc"]
        if yaw == None:
            yaw = self.attr_dict["sdk_yaw_acc"]
        result = get_result(self.rotate_with_speed(yaw, pitch))
        return result

    def update_angle(
        self,
        pitch=None,
        yaw=None,
        pspeed=None,
        yspeed=None,
        coordinate=rm_define.gimbal_coodrdinate_4,
        wait_for_complete=True,
    ):
        kw = {}
        if pspeed != None:
            kw["pitch_speed"] = pspeed
        if yspeed != None:
            kw["yaw_speed"] = yspeed
        if coordinate != None:
            kw["coordinate"] = coordinate
        result = get_result(
            self.__angle_ctrl(yaw, pitch, wait_for_complete=wait_for_complete, **kw)
        )

    def update_angle_based_on_origin(
        self, dp=None, dy=None, pspeed=None, yspeed=None, wait_for_complete=True
    ):
        if wait_for_complete == None:
            wait_for_complete = True
        self.update_angle(
            dp,
            dy,
            pspeed,
            yspeed,
            rm_define.gimbal_coodrdinate_4,
            wait_for_complete=wait_for_complete,
        )

    def update_angle_based_on_cur(
        self, dp=None, dy=None, pspeed=None, yspeed=None, wait_for_complete=True
    ):
        if wait_for_complete == None:
            wait_for_complete = True
        self.update_angle(
            dp,
            dy,
            pspeed,
            yspeed,
            rm_define.gimbal_coodrdinate_cur,
            wait_for_complete=wait_for_complete,
        )

    def update_status(self, status):
        result = False
        if status == rm_define.gimbal_status.wake:
            result = get_result(self.resume())
        elif status == rm_define.gimbal_status.sleep:
            result = get_result(self.suspend())
        else:
            return result

    def get_angle(self):
        return self.get_axis_angle()

    def recenter(self, *xargs):
        if len(xargs) == 0:
            self.__recenter()
        elif len(xargs) == 1:
            result = get_result(self.__recenter(xargs[0], xargs[0]))
            return result
        elif len(xargs) == 2:
            result = get_result(self.__recenter(xargs[0], xargs[1]))
            return result
        else:
            return False


class LedCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.led = rm_module.Led(event_client)
        self.event_client = event_client
        self.flash_freq = 2

    @event_register
    def init(self):
        logger.info("LED_CTRL: init")
        self.fire_led_off()
        self.reset()
        return get_task_dict(None, "action_immediate", (), {})

    @event_register
    def reset(self):
        logger.info("LED_CTRL: reset")
        duss_result = rm_define.DUSS_SUCCESS
        duss_result = self.led.set_led(
            rm_define.armor_all, 1, 7, 0, 127, 70, 0, 1000, 1000
        )
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    @event_register
    def set_led(self, comp, r, g, b, effect):
        CHECK_VALUE_IN_ENUM_LIST(
            comp,
            **{
                "rm_define.armor_all": rm_define.armor_all,
                "rm_define.armor_top_all": rm_define.armor_top_all,
                "rm_define.armor_top_right": rm_define.armor_top_right,
                "rm_define.armor_top_left": rm_define.armor_top_left,
                "rm_define.armor_bottom_all": rm_define.armor_bottom_all,
                "rm_define.armor_bottom_back": rm_define.armor_bottom_back,
                "rm_define.armor_bottom_front": rm_define.armor_bottom_front,
                "rm_define.armor_bottom_left": rm_define.armor_bottom_left,
                "rm_define.armor_bottom_right": rm_define.armor_bottom_right,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(r, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(g, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(b, 0, 255, int)
        CHECK_VALUE_IN_ENUM_LIST(
            effect,
            **{
                "rm_define.effect_always_on": rm_define.effect_always_on,
                "rm_define.effect_always_off": rm_define.effect_always_off,
                "rm_define.effect_breath": rm_define.effect_breath,
                "rm_define.effect_flash": rm_define.effect_flash,
                "rm_define.effect_marquee": rm_define.effect_marquee,
            }
        )

        logger.info(
            "LED_CTRL: set, comp is %s, r is %s, g is %s, b is %s, effect is %s"
            % (comp, r, g, b, effect)
        )
        duss_result = rm_define.DUSS_SUCCESS
        if effect == rm_define.effect_always_on:
            duss_result = self.led.set_led(comp, 1, 7, r, g, b, 0, 1000, 1000)
        elif effect == rm_define.effect_always_off:
            duss_result = self.led.set_led(comp, 0, 7, r, g, b, 0, 1000, 1000)
        elif effect == rm_define.effect_breath:
            duss_result = self.led.set_led(comp, 2, 7, r, g, b, 0, 1000, 1000)
        elif effect == rm_define.effect_flash:
            t = int(500 / self.flash_freq)
            duss_result = self.led.set_led(comp, 3, 7, r, g, b, 0, t, t)
        elif effect == rm_define.effect_marquee:
            duss_result = self.led.set_led(comp, 4, 7, r, g, b, 0, 30, 40, True)
        return get_task_dict(
            None, "action_immediate", (comp, r, g, b, effect), result=duss_result
        )

    @event_register
    def set_top_led(self, comp, r, g, b, effect):
        logger.info(
            "LED_CTRL: set top, comp is %s, r is %s, g is %s, b is %s, effect is %s"
            % (comp, r, g, b, effect)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            comp,
            **{
                "rm_define.armor_top_all": rm_define.armor_top_all,
                "rm_define.armor_top_right": rm_define.armor_top_right,
                "rm_define.armor_top_left": rm_define.armor_top_left,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(r, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(g, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(b, 0, 255, int)
        CHECK_VALUE_IN_ENUM_LIST(
            effect,
            **{
                "rm_define.effect_always_on": rm_define.effect_always_on,
                "rm_define.effect_always_off": rm_define.effect_always_off,
                "rm_define.effect_breath": rm_define.effect_breath,
                "rm_define.effect_flash": rm_define.effect_flash,
                "rm_define.effect_marquee": rm_define.effect_marquee,
            }
        )
        duss_result = rm_define.DUSS_SUCCESS
        _, _, _, duss_result = self.set_led(comp, r, g, b, effect)
        return get_task_dict(
            None, "action_immediate", (comp, r, g, b, effect), result=duss_result
        )

    @event_register
    def set_bottom_led(self, comp, r, g, b, effect):
        logger.info(
            "LED_CTRL: set bottom, comp is %s, r is %s, g is %s, b is %s, effect is %s"
            % (comp, r, g, b, effect)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            comp,
            **{
                "rm_define.armor_bottom_all": rm_define.armor_bottom_all,
                "rm_define.armor_bottom_back": rm_define.armor_bottom_back,
                "rm_define.armor_bottom_front": rm_define.armor_bottom_front,
                "rm_define.armor_bottom_left": rm_define.armor_bottom_left,
                "rm_define.armor_bottom_right": rm_define.armor_bottom_right,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(r, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(g, 0, 255, int)
        CHECK_VALUE_RANGE_AND_TYPE(b, 0, 255, int)
        CHECK_VALUE_IN_ENUM_LIST(
            effect,
            **{
                "rm_define.effect_always_on": rm_define.effect_always_on,
                "rm_define.effect_always_off": rm_define.effect_always_off,
                "rm_define.effect_breath": rm_define.effect_breath,
                "rm_define.effect_flash": rm_define.effect_flash,
            }
        )

        duss_result = rm_define.DUSS_SUCCESS
        _, _, _, duss_result = self.set_led(comp, r, g, b, effect)
        return get_task_dict(
            None, "action_immediate", (comp, r, g, b, effect), result=duss_result
        )

    @event_register
    def set_single_led(self, comp, idx, effect, **kw):
        logger.info(
            "LED_CTRL: set single, comp is %s, idx is %s, effect is %s"
            % (comp, str(idx), effect)
        )
        CHECK_VALUE_IN_ENUM_LIST(
            comp,
            **{
                "rm_define.armor_top_all": rm_define.armor_top_all,
                "rm_define.armor_top_left": rm_define.armor_top_left,
                "rm_define.armor_top_right": rm_define.armor_top_right,
            }
        )
        CHECK_VALUE_IN_ENUM_LIST(
            effect,
            **{
                "rm_define.effect_always_on": rm_define.effect_always_on,
                "rm_define.effect_always_off": rm_define.effect_always_off,
            }
        )
        CHECK_VALUE_TYPE(idx, int, list, rm_builtins.RmList)
        duss_result = rm_define.DUSS_SUCCESS
        mask = 0
        if type(idx) == list or isinstance(idx, rm_builtins.RmList):
            idx = list(idx)
            for i in idx:
                CHECK_VALUE_RANGE(
                    i,
                    rm_define.armor_top_led_index_min,
                    rm_define.armor_top_led_index_max,
                )
                if (
                    i >= rm_define.armor_top_led_index_min
                    and i <= rm_define.armor_top_led_index_max
                ):
                    mask = mask | 1 << (i - 1)
        elif type(idx) == int:
            CHECK_VALUE_RANGE(
                idx,
                rm_define.armor_top_led_index_min,
                rm_define.armor_top_led_index_max,
            )
            if (
                idx >= rm_define.armor_top_led_index_min
                and idx <= rm_define.armor_top_led_index_max
            ):
                mask = 1 << (idx - 1)
        else:
            logger.error("LED_CTRL: set single, led index value type error")
            err_code = rm_define.BLOCK_ERR_VALUE_TYPE
            return get_task_dict(
                None,
                "action_immediate",
                (comp, idx, effect, kw),
                result=duss_result,
                err_code=err_code,
            )

        duss_result = self.led.set_single_led(comp, mask, effect, **kw)
        return get_task_dict(
            None, "action_immediate", (comp, idx, effect, kw), result=duss_result
        )

    @event_register
    def set_flash(self, comp, freq):
        CHECK_VALUE_IN_ENUM_LIST(
            comp,
            **{
                "rm_define.armor_all": rm_define.armor_all,
                "rm_define.armor_bottom_back": rm_define.armor_bottom_back,
                "rm_define.armor_bottom_front": rm_define.armor_bottom_front,
                "rm_define.armor_bottom_left": rm_define.armor_bottom_left,
                "rm_define.armor_bottom_right": rm_define.armor_bottom_right,
                "rm_define.armor_top_left": rm_define.armor_top_left,
                "rm_define.armor_top_right": rm_define.armor_top_right,
            }
        )
        CHECK_VALUE_RANGE_AND_TYPE(freq, 1, 10, int)
        logger.info("LED_CTRL: set flash, comp is %s, freq is %s" % (comp, freq))
        duss_result = rm_define.DUSS_SUCCESS
        if freq > 0:
            self.flash_freq = freq
            duss_result = self.led.set_flash(comp, freq)
        else:
            duss_result = rm_define.DUSS_ERR_PARAM

        return get_task_dict(None, "action_immediate", (comp, freq), result=duss_result)

    @event_register
    def turn_off(self, comp):
        logger.info("LED_CTRL: turn off, comp is %s" % (comp))
        duss_result = rm_define.DUSS_SUCCESS
        _, _, _, duss_result = self.set_led(comp, 0, 0, 0, rm_define.effect_always_off)
        return get_task_dict(None, "action_immediate", (comp), result=duss_result)

    def fire_led_on(self):
        logger.info("LED_CTRL: turn on gun fire led")
        self.led.set_gun_led(0, 1)

    def fire_led_off(self):
        logger.info("LED_CTRL: turn off gun fire led")
        self.led.set_gun_led(0, 0)

    def gun_led_on(self):
        logger.info("LED_CTRL: turn on gun led")
        self.led.set_gun_led(7, 1)

    def gun_led_off(self):
        logger.info("LED_CTRL: turn off gun led")
        self.led.set_gun_led(7, 0)

    def stop(self):
        logger.info("LED_CTRL: stop")
        self.reset()

    ## Python API ##
    def update_led(self, comp, effect, **kw):
        r = 255
        g = 255
        b = 255
        blink_frequency = 2
        single_led_index = False

        if "r" in kw.keys():
            r = kw["r"]
        if "g" in kw.keys():
            g = kw["g"]
        if "b" in kw.keys():
            b = kw["b"]
        if "blink_frequency" in kw.keys():
            blink_frequency = kw["blink_frequency"]
        elif "blink_frequency" in kw.keys():
            blink_frequency = kw["blink_freq"]
        if "single_led_index" in kw.keys():
            single_led_index = kw["single_led_index"]

        if (
            effect == rm_define.led_effect.pulse
            or effect == rm_define.led_effect.scrolling
        ):
            return get_result(self.set_led(comp, r, g, b, effect))
        elif effect == rm_define.led_effect.solid:
            if single_led_index != False:
                return get_result(
                    self.set_single_led(comp, single_led_index, effect, r=r, g=g, b=b)
                )
            else:
                return get_result(self.set_led(comp, r, g, b, effect))
        elif effect == rm_define.led_effect.blink:
            self.set_flash(comp, blink_frequency)
            return get_result(self.set_led(comp, r, g, b, effect))
        elif effect == rm_define.led_effect.off:
            if single_led_index != False:
                return get_result(
                    self.set_single_led(comp, single_led_index, effect, r=r, g=g, b=b)
                )
            else:
                return get_result(self.turn_off(comp))
        else:
            return False

    def update_led_t(self, comp, effect, r, g, b, blink_freq, single_led_index):
        attr_keys = {
            "all": rm_define.armor_all,
            "top_all": rm_define.armor_top_all,
            "top_left": rm_define.armor_top_left,
            "top_right": rm_define.armor_top_right,
            "bottom_all": rm_define.armor_bottom_all,
            "bottom_left": rm_define.armor_bottom_left,
            "bottom_right": rm_define.armor_bottom_right,
            "bottom_front": rm_define.armor_bottom_front,
            "bottom_back": rm_define.armor_bottom_back,
            "pulse": rm_define.effect_breath,
            "blink": rm_define.effect_flash,
            "scrolling": rm_define.effect_marquee,
            "solid": rm_define.effect_always_on,
            "off": rm_define.effect_always_off,
        }

        kw = {}
        if r != None:
            kw["r"] = r
        if g != None:
            kw["g"] = g
        if b != None:
            kw["b"] = b
        if blink_freq:
            kw["blink_freq"] = blink_freq
        if single_led_index:
            kw["single_led_index"] = single_led_index

        if comp:
            comp_list = comp.split("|")
            comp = 0
            for i in comp_list:
                if i in attr_keys.keys():
                    comp |= attr_keys[i]

        if effect in attr_keys.keys():
            effect = attr_keys[effect]
        else:
            logger.info("effect key error")
            return False

        self.update_led(comp, effect, **kw)


class ArmorCtrl(RobotCtrlTool):
    EVENT_HIT_BOTTOM_FRONT_STR = "armor_hit_detection_bottom_front"
    EVENT_HIT_BOTTOM_BACK_STR = "armor_hit_detection_bottom_back"
    EVENT_HIT_BOTTOM_LEFT_STR = "armor_hit_detection_bottom_left"
    EVENT_HIT_BOTTOM_RIGHT_STR = "armor_hit_detection_bottom_right"
    EVENT_HIT_TOP_LEFT_STR = "armor_hit_detection_top_left"
    EVENT_HIT_TOP_RIGHT_STR = "armor_hit_detection_top_right"
    EVENT_HIT_ALL_STR = "armor_hit_detection_all"
    EVENT_IR_HIT_TOP_LEFT_STR = "ir_hit_detection_top_left"
    EVENT_IR_HIT_TOP_RIGHT_STR = "ir_hit_detection_top_right"
    EVENT_IR_HIT_ALL_STR = "ir_hit_detection_event"
    ARMOR_BOTTOM_FRONT_STR = "armor_bottom_front"
    ARMOR_BOTTOM_BACK_STR = "armor_bottom_back"
    ARMOR_BOTTOM_LEFT_STR = "armor_bottom_left"
    ARMOR_BOTTOM_RIGHT_STR = "armor_bottom_right"
    ARMOR_TOP_LEFT_STR = "armor_top_left"
    ARMOR_TOP_RIGHT_STR = "armor_top_right"
    ARMOR_ALL_STR = "armor_all"
    IR_TOP_LEFT_STR = "ir_top_left"
    IR_TOP_RIGHT_STR = "ir_top_right"
    IR_ALL_STR = "ir_all"

    def __init__(self, event_client):
        super().__init__(event_client)
        self.armor = rm_module.Armor(event_client)
        self.event_client = event_client

        self.hit_type = None
        self.hit_index = None
        self.condition_event_table = {}
        self.condition_event_time = {}
        self.condition_wait_event_list = {}
        self.condition_mutex = threading.Lock()
        self.last_hit_armor = -1
        self.hit_sensitivity = 5
        self.event_init()

        self.sdk_event_push_callback = None
        self.sdk_hit_event_push_enable_flag = False

    def event_init(self):
        self.hit_event_cb = []
        self.hit_event_cb.append(ArmorCtrl.ARMOR_ALL_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_BOTTOM_FRONT_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_BOTTOM_BACK_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_BOTTOM_LEFT_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_TOP_LEFT_STR)
        self.hit_event_cb.append(ArmorCtrl.ARMOR_TOP_RIGHT_STR)
        self.hit_event_cb.append(ArmorCtrl.IR_TOP_LEFT_STR)
        self.hit_event_cb.append(ArmorCtrl.IR_TOP_RIGHT_STR)
        self.hit_event_cb.append(ArmorCtrl.IR_ALL_STR)

        for event_name in self.hit_event_cb:
            self.event_client.event_callback_register(event_name, dummy_callback)

        self.armor.hit_event_register(self.hit_event_process)
        self.armor.ir_event_register(self.ir_event_process)

    def sdk_event_push_callback_register(self, cb):
        if callable(cb):
            self.sdk_event_push_callback = cb

    def sdk_event_push_enable_flag_set(self, hit_flag=None, reserve=None):
        if hit_flag == "on":
            self.sdk_hit_event_push_enable_flag = True
            return True
        elif hit_flag == "off":
            self.sdk_hit_event_push_enable_flag = False
            return True
        else:
            return False

    # called by event_client thread
    def hit_event_process(self, event_client, msg):
        logger.info("ARMOR_CTRL: HIT EVENT PROCESS.")
        data = msg["data"]
        self.hit_index = (data[0] >> 4) & 0x0F
        self.hit_type = data[0] & 0x0F
        self.last_hit_armor = self.hit_index

        callback_data = self.hit_index

        event = {}
        if self.sdk_event_push_callback:
            if self.sdk_hit_event_push_enable_flag:
                event["hit"] = (self.hit_index, self.hit_type)
            self.sdk_event_push_callback(event)

        if ArmorCtrl.ARMOR_ALL_STR in self.hit_event_cb:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_ALL_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_ALL_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_ALL_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(
                ArmorCtrl.ARMOR_ALL_STR, callback_data
            )
            logger.info("ARMOR_CTRL: trigger armor all hit event.")
        if self.hit_index == 1 and ArmorCtrl.ARMOR_BOTTOM_BACK_STR in self.hit_event_cb:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_BOTTOM_BACK_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_BOTTOM_BACK_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_BOTTOM_BACK_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_BOTTOM_BACK_STR)
            logger.info("ARMOR_CTRL: trigger armor bottom back hit event.")
        if (
            self.hit_index == 2
            and ArmorCtrl.ARMOR_BOTTOM_FRONT_STR in self.hit_event_cb
        ):
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_BOTTOM_FRONT_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_BOTTOM_FRONT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_BOTTOM_FRONT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_BOTTOM_FRONT_STR)
            logger.info("ARMOR_CTRL: trigger armor bottom front hit event.")
        if self.hit_index == 3 and ArmorCtrl.ARMOR_BOTTOM_LEFT_STR in self.hit_event_cb:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_BOTTOM_LEFT_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_BOTTOM_LEFT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_BOTTOM_LEFT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_BOTTOM_LEFT_STR)
            logger.info("ARMOR_CTRL: trigger armor bottom left hit event.")
        if (
            self.hit_index == 4
            and ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR in self.hit_event_cb
        ):
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR)
            logger.info("ARMOR_CTRL: trigger armor bottom right hit event.")
        if self.hit_index == 5 and ArmorCtrl.ARMOR_TOP_LEFT_STR in self.hit_event_cb:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_TOP_LEFT_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_TOP_LEFT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_TOP_LEFT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_TOP_LEFT_STR)
            logger.info("ARMOR_CTRL: trigger armor top left hit event.")
        if self.hit_index == 6 and ArmorCtrl.ARMOR_TOP_RIGHT_STR in self.hit_event_cb:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.ARMOR_TOP_RIGHT_STR)
            self.condition_event_time[ArmorCtrl.ARMOR_TOP_RIGHT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.ARMOR_TOP_RIGHT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.ARMOR_TOP_RIGHT_STR)
            logger.info("ARMOR_CTRL: trigger armor top right hit event.")

    # called by event_client thread
    def ir_event_process(self, event_client, msg):
        logger.info("ARMOR_CTRL: IR EVENT PROCESS.")
        data = msg["data"]
        self.ir_recv_dev = data[1] & 0x0F

        if ArmorCtrl.IR_TOP_LEFT_STR in self.hit_event_cb and (self.ir_recv_dev & 0x01):
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.IR_TOP_LEFT_STR)
            self.condition_event_time[ArmorCtrl.IR_TOP_LEFT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.IR_TOP_LEFT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.IR_TOP_LEFT_STR)
            logger.info("ARMOR_CTRL: trigger ir top left hit event.")
        if ArmorCtrl.IR_TOP_RIGHT_STR in self.hit_event_cb and (
            self.ir_recv_dev & 0x02
        ):
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.IR_TOP_RIGHT_STR)
            self.condition_event_time[ArmorCtrl.IR_TOP_RIGHT_STR] = time.time()
            self.condition_event_table[ArmorCtrl.IR_TOP_RIGHT_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.IR_TOP_RIGHT_STR)
            logger.info("ARMOR_CTRL: trigger ir top right hit event.")
        if ArmorCtrl.IR_ALL_STR in self.hit_event_cb and self.ir_recv_dev:
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(ArmorCtrl.IR_ALL_STR)
            self.condition_event_time[ArmorCtrl.IR_ALL_STR] = time.time()
            self.condition_event_table[ArmorCtrl.IR_ALL_STR] = True
            self.condition_mutex.release()
            self.event_client.event_come_to_process(ArmorCtrl.IR_ALL_STR)
            logger.info("ARMOR_CTRL: trigger ir hit event.")

    def register_event(self, func_dict):
        if ArmorCtrl.EVENT_HIT_ALL_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_ALL_STR, func_dict[ArmorCtrl.EVENT_HIT_ALL_STR]
            )
            logger.info("ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_ALL_STR))
        if ArmorCtrl.EVENT_HIT_BOTTOM_FRONT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_BOTTOM_FRONT_STR,
                func_dict[ArmorCtrl.EVENT_HIT_BOTTOM_FRONT_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_BOTTOM_FRONT_STR)
            )
        if ArmorCtrl.EVENT_HIT_BOTTOM_BACK_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_BOTTOM_BACK_STR,
                func_dict[ArmorCtrl.EVENT_HIT_BOTTOM_BACK_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_BOTTOM_BACK_STR)
            )
        if ArmorCtrl.EVENT_HIT_BOTTOM_LEFT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_BOTTOM_LEFT_STR,
                func_dict[ArmorCtrl.EVENT_HIT_BOTTOM_LEFT_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_BOTTOM_LEFT_STR)
            )
        if ArmorCtrl.EVENT_HIT_BOTTOM_RIGHT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR,
                func_dict[ArmorCtrl.EVENT_HIT_BOTTOM_RIGHT_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_BOTTOM_RIGHT_STR)
            )
        if ArmorCtrl.EVENT_HIT_TOP_LEFT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_TOP_LEFT_STR,
                func_dict[ArmorCtrl.EVENT_HIT_TOP_LEFT_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_TOP_LEFT_STR)
            )
        if ArmorCtrl.EVENT_HIT_TOP_RIGHT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.ARMOR_TOP_RIGHT_STR,
                func_dict[ArmorCtrl.EVENT_HIT_TOP_RIGHT_STR],
            )
            logger.info(
                "ARMOR_CTRL: register event  %s" % (ArmorCtrl.ARMOR_TOP_RIGHT_STR)
            )
        if ArmorCtrl.EVENT_IR_HIT_TOP_LEFT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.IR_TOP_LEFT_STR,
                func_dict[ArmorCtrl.EVENT_IR_HIT_TOP_LEFT_STR],
            )
            logger.info("ARMOR_CTRL: register event  %s" % (ArmorCtrl.IR_TOP_LEFT_STR))
        if ArmorCtrl.EVENT_IR_HIT_TOP_RIGHT_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.IR_TOP_RIGHT_STR,
                func_dict[ArmorCtrl.EVENT_IR_HIT_TOP_RIGHT_STR],
            )
            logger.info("ARMOR_CTRL: register event  %s" % (ArmorCtrl.IR_TOP_RIGHT_STR))
        if ArmorCtrl.EVENT_IR_HIT_ALL_STR in func_dict.keys():
            self.event_client.event_callback_register(
                ArmorCtrl.IR_ALL_STR, func_dict[ArmorCtrl.EVENT_IR_HIT_ALL_STR]
            )
            logger.info("ARMOR_CTRL: register event  %s" % (ArmorCtrl.IR_ALL_STR))

    def cond_wait(self, func_str):
        logger.info("ARMOR_CTRL: cond wait, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(
            func_str,
            **{
                "rm_define.cond_armor_hit": rm_define.cond_armor_hit,
                "rm_define.cond_armor_bottom_front_hit": rm_define.cond_armor_bottom_front_hit,
                "rm_define.cond_armor_bottom_back_hit": rm_define.cond_armor_bottom_back_hit,
                "rm_define.cond_armor_bottom_left_hit": rm_define.cond_armor_bottom_left_hit,
                "rm_define.cond_armor_bottom_right_hit": rm_define.cond_armor_bottom_right_hit,
                "rm_define.cond_armor_top_left_hit": rm_define.cond_armor_top_left_hit,
                "rm_define.cond_armor_top_right_hit": rm_define.cond_armor_top_right_hit,
                "rm_define.cond_ir_top_left_hit": rm_define.cond_ir_top_left_hit,
                "rm_define.cond_ir_top_right_hit": rm_define.cond_ir_top_right_hit,
                "rm_define.cond_ir_hit_detection": rm_define.cond_ir_hit_detection,
            }
        )
        condition_wait_event = threading.Event()
        self._cond_wait_register(func_str, condition_wait_event)
        self.robot_sleep(3600 * 1000, self.check_cond_wait_event, func_str)
        self._cond_wait_unregister(func_str)

    def check_condition(self, func_str):
        logger.info("ARMOR_CTRL: check condition, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(
            func_str,
            **{
                "rm_define.cond_armor_hit": rm_define.cond_armor_hit,
                "rm_define.cond_armor_bottom_front_hit": rm_define.cond_armor_bottom_front_hit,
                "rm_define.cond_armor_bottom_back_hit": rm_define.cond_armor_bottom_back_hit,
                "rm_define.cond_armor_bottom_left_hit": rm_define.cond_armor_bottom_left_hit,
                "rm_define.cond_armor_bottom_right_hit": rm_define.cond_armor_bottom_right_hit,
                "rm_define.cond_armor_top_left_hit": rm_define.cond_armor_top_left_hit,
                "rm_define.cond_armor_top_right_hit": rm_define.cond_armor_top_right_hit,
                "rm_define.cond_ir_top_left_hit": rm_define.cond_ir_top_left_hit,
                "rm_define.cond_ir_top_right_hit": rm_define.cond_ir_top_right_hit,
                "rm_define.cond_ir_hit_detection": rm_define.cond_ir_hit_detection,
            }
        )
        self.condition_mutex.acquire()
        event_happen = False
        if func_str in self.condition_event_table.keys():
            curr_time = time.time()
            if curr_time - self.condition_event_time[func_str] > 1.0:
                self.condition_event_table[func_str] = False
            event_happen = self.condition_event_table[func_str]
            self.condition_event_table[func_str] = False
        self.condition_mutex.release()
        return event_happen

    def check_cond_wait_event(self, func_str):
        logger.info("ARMOR_CTRL: check cond wait event, func_str is %s" % (func_str))
        event_state = False
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            event_state = self.condition_wait_event_list[func_str].isSet()
        self.condition_mutex.release()
        return event_state

    def _cond_wait_register(self, func_str, wait_event):
        self.condition_mutex.acquire()
        wait_event.clear()
        self.condition_wait_event_list[func_str] = wait_event
        self.condition_mutex.release()

    def _cond_wait_unregister(self, func_str):
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list.pop(func_str)
        self.condition_mutex.release()

    def _wakeup_condition_waiting(self, func_str):
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list[func_str].set()

    def get_last_hit_armor(self):
        logger.info("ARMOR_CTRL: last hit armor = " + str(self.last_hit_armor))
        return self.last_hit_armor

    def get_last_hit_index(self):
        duss_result, resp = self.armor.hit_event_query()
        logger.info(duss_result)
        logger.info(resp)
        if duss_result != rm_define.DUSS_SUCCESS:
            return -1
        else:
            data = resp["data"]
            return data[1]

    def get_last_hit_time(self):
        duss_result, resp = self.armor.hit_event_query()
        if duss_result != rm_define.DUSS_SUCCESS:
            return -1
        else:
            data = resp["data"]
            hit_unixtime = (
                tools.byte_to_uint32(data[2:6]) << 32 | tools.byte_to_uint32(data[6:10])
            ) / 1000.0
            return hit_unixtime

    @event_register
    def set_hit_sensitivity(self, level):
        CHECK_VALUE_RANGE_AND_TYPE(level, 0, 10, int)
        level = tools.data_limit(level, 0, 10)
        self.hit_sensitivity = level
        k = 1.5 - level / 10
        result = self.armor.set_hit_sensitivity(k)
        logger.info("set hit sensitivity %s success!" % level)
        return get_task_dict(None, "action_immediate", (level), result=result)

    def get_hit_sensitivity(self):
        return self.hit_sensitivity

    def reset_hit_sensitivity(self):
        self.set_hit_sensitivity(5)

    def stop(self):
        logger.info("ARMOR_CTRL: stop")

    def exit(self):
        logger.info("ARMOR_CTRL: exit")
        self.reset_hit_sensitivity()
        self.armor.hit_event_unregister()

    ## Python API ##
    def get_last_hit_info(self):
        return (self.get_last_hit_index(), self.get_last_hit_time())

    def set_sensitivity(self, value):
        return get_result(self.set_hit_sensitivity(value))


class VisionCtrl(RobotCtrlTool):
    event_detection_type_dict = {
        rm_define.vision_detection_people_type: {
            rm_define.detection_all_default: "vision_recognized_people"
        },
        rm_define.vision_detection_head_shoulder_type: {
            rm_define.detection_all_default: "vision_recognized_head_shoulder"
        },
        rm_define.vision_detection_line_type: {
            rm_define.detection_all_default: "vision_recognized_line"
        },
        rm_define.vision_detection_car_type: {
            rm_define.detection_all_default: "vision_recognized_car"
        },
        rm_define.vision_detection_pose_type: {
            rm_define.pose_all: "vision_recognized_pose_all",
            rm_define.pose_victory: "vision_recognized_pose_victory",
            rm_define.pose_give_in: "vision_recognized_pose_give_in",
            rm_define.pose_capture: "vision_recognized_pose_capture",
            rm_define.pose_left_hand_up: "vision_recognized_pose_left_hand_up",
            rm_define.pose_right_hand_up: "vision_recognized_pose_right_hand_up",
        },
        rm_define.vision_detection_marker_type: {
            rm_define.marker_all: "vision_recognized_marker_all",
            rm_define.marker_trans_stop: "vision_recognized_marker_trans_stop",
            rm_define.marker_trans_dice: "vision_recognized_marker_trans_dice",
            rm_define.marker_trans_target: "vision_recognized_marker_trans_target",
            rm_define.marker_trans_left: "vision_recognized_marker_trans_left",
            rm_define.marker_trans_right: "vision_recognized_marker_trans_right",
            rm_define.marker_trans_forward: "vision_recognized_marker_trans_forward",
            rm_define.marker_trans_backward: "vision_recognized_marker_trans_backward",
            rm_define.marker_trans_red_heart: "vision_recognized_marker_trans_red_heart",
            rm_define.marker_trans_sword: "vision_recognized_marker_trans_sword",
            rm_define.marker_number_zero: "vision_recognized_marker_number_zero",
            rm_define.marker_number_one: "vision_recognized_marker_number_one",
            rm_define.marker_number_two: "vision_recognized_marker_number_two",
            rm_define.marker_number_three: "vision_recognized_marker_number_three",
            rm_define.marker_number_four: "vision_recognized_marker_number_four",
            rm_define.marker_number_five: "vision_recognized_marker_number_five",
            rm_define.marker_number_six: "vision_recognized_marker_number_six",
            rm_define.marker_number_seven: "vision_recognized_marker_number_seven",
            rm_define.marker_number_eight: "vision_recognized_marker_number_eight",
            rm_define.marker_number_nine: "vision_recognized_marker_number_nine",
            rm_define.marker_letter_A: "vision_recognized_marker_letter_A",
            rm_define.marker_letter_B: "vision_recognized_marker_letter_B",
            rm_define.marker_letter_C: "vision_recognized_marker_letter_C",
            rm_define.marker_letter_D: "vision_recognized_marker_letter_D",
            rm_define.marker_letter_E: "vision_recognized_marker_letter_E",
            rm_define.marker_letter_F: "vision_recognized_marker_letter_F",
            rm_define.marker_letter_G: "vision_recognized_marker_letter_G",
            rm_define.marker_letter_H: "vision_recognized_marker_letter_H",
            rm_define.marker_letter_I: "vision_recognized_marker_letter_I",
            rm_define.marker_letter_J: "vision_recognized_marker_letter_J",
            rm_define.marker_letter_K: "vision_recognized_marker_letter_K",
            rm_define.marker_letter_L: "vision_recognized_marker_letter_L",
            rm_define.marker_letter_M: "vision_recognized_marker_letter_M",
            rm_define.marker_letter_N: "vision_recognized_marker_letter_N",
            rm_define.marker_letter_O: "vision_recognized_marker_letter_O",
            rm_define.marker_letter_P: "vision_recognized_marker_letter_P",
            rm_define.marker_letter_Q: "vision_recognized_marker_letter_Q",
            rm_define.marker_letter_R: "vision_recognized_marker_letter_R",
            rm_define.marker_letter_S: "vision_recognized_marker_letter_S",
            rm_define.marker_letter_T: "vision_recognized_marker_letter_T",
            rm_define.marker_letter_U: "vision_recognized_marker_letter_U",
            rm_define.marker_letter_V: "vision_recognized_marker_letter_V",
            rm_define.marker_letter_W: "vision_recognized_marker_letter_W",
            rm_define.marker_letter_X: "vision_recognized_marker_letter_X",
            rm_define.marker_letter_Y: "vision_recognized_marker_letter_Y",
            rm_define.marker_letter_Z: "vision_recognized_marker_letter_Z",
            rm_define.marker_all_with_follow_line: "vision_recognized_marker_all_with_follow_line",
            rm_define.marker_number_all: "vision_recognized_marker_number_all",
            rm_define.marker_letter_all: "vision_recognized_marker_letter_all",
            rm_define.marker_trans_all: "vision_recognized_marker_trans_all",
        },
    }

    event_detection_mask_dict = {
        "vision_recognized_people": rm_define.vision_detection_people,
        "vision_recognized_head_shoulder": rm_define.vision_detection_head_shoulder,
        "vision_recognized_pose_victory": rm_define.vision_detection_pose,
        "vision_recognized_pose_give_in": rm_define.vision_detection_pose,
        "vision_recognized_pose_capture": rm_define.vision_detection_pose,
        "vision_recognized_pose_left_hand_up": rm_define.vision_detection_pose,
        "vision_recognized_pose_right_hand_up": rm_define.vision_detection_pose,
        "vision_recognized_pose_all": rm_define.vision_detection_pose,
        "vision_recognized_line": rm_define.vision_detection_line,
        "vision_recognized_car": rm_define.vision_detection_car,
        "vision_recognized_marker_trans_stop": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_dice": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_target": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_left": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_right": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_forward": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_backward": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_red_heart": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_sword": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_zero": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_one": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_two": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_three": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_four": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_five": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_six": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_seven": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_eight": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_nine": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_A": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_B": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_C": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_D": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_E": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_F": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_G": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_H": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_I": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_J": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_K": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_L": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_M": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_N": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_O": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_P": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_Q": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_R": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_S": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_T": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_U": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_V": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_W": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_X": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_Y": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_Z": rm_define.vision_detection_marker,
        "vision_recognized_marker_all": rm_define.vision_detection_marker,
        "vision_recognized_marker_all_with_follow_line": rm_define.vision_detection_marker,
        "vision_recognized_marker_number_all": rm_define.vision_detection_marker,
        "vision_recognized_marker_letter_all": rm_define.vision_detection_marker,
        "vision_recognized_marker_trans_all": rm_define.vision_detection_marker,
    }

    event_detection_callback_need_data_dict = {
        "vision_recognized_pose_all": True,
        "vision_recognized_marker_all": True,
        "vision_recognized_line": True,
        "vision_recognized_marker_all_with_follow_line": True,
        "vision_recognized_car": True,
    }

    line_follow_exception_detection_type_list = [rm_define.vision_detection_marker_type]

    detection_enable_priority = {
        rm_define.vision_detection_auto_aim: {
            "core_num": rm_define.vision_core_num_10,
            "priority": rm_define.vision_priority_auto_aim,
        },
        rm_define.vision_detection_people: {
            "core_num": rm_define.vision_core_num_8,
            "priority": rm_define.vision_priority_people,
        },
        rm_define.vision_detection_pose: {
            "core_num": rm_define.vision_core_num_8,
            "priority": rm_define.vision_priority_pose,
        },
        rm_define.vision_detection_head_shoulder: {
            "core_num": rm_define.vision_core_num_8,
            "priority": rm_define.vision_priority_head_shoulder,
        },
        rm_define.vision_detection_marker: {
            "core_num": rm_define.vision_core_num_8,
            "priority": rm_define.vision_priority_marker,
        },
        rm_define.vision_detection_line: {
            "core_num": rm_define.vision_core_num_4,
            "priority": rm_define.vision_priority_line,
        },
        rm_define.vision_detection_people_follow: {
            "core_num": rm_define.vision_core_num_4,
            "priority": rm_define.vision_priority_people_follow,
        },
        rm_define.vision_detection_car: {
            "core_num": rm_define.vision_core_num_4,
            "priority": rm_define.vision_priority_car,
        },
    }

    class ImageInfo(object):
        class Pos(object):
            def __init__(self):
                self.x = 0
                self.y = 0

        class Size(object):
            def __init__(self):
                self.w = 0
                self.h = 0

        def __init__(self):
            self.type = 0
            self.info = 0
            self.distance = 0
            self.pos = self.Pos()
            self.size = self.Size()

        def output_format(self):
            return "type: %s, info %s, distance:%s, x: %f, y:%f, w:%f, h:%f" % (
                self.type,
                self.info,
                self.distance,
                self.pos.x,
                self.pos.y,
                self.size.w,
                self.size.h,
            )

    def __init__(self, event_client):
        super().__init__(event_client)
        self.vision = rm_module.Vision(event_client)
        self.media_ctrl = MediaCtrl(event_client)
        self.event_client = event_client

        self.recognition_event_cb = []
        self.condition_event_table = {}
        self.condition_event_time = {}
        self.condition_wait_event_list = {}
        self.condition_mutex = threading.Lock()
        self.vision_detection_data_mutex = threading.Lock()

        self.wait_event_callback_need_data_set = set()
        self.finish = False

        self.marker_detection_distance = 500
        self.marker_detection_result_info = []

        # line follow
        self.line_follow_auto_ctrl_thread = None
        self.line_lost_first_chassis_stop_flag = True
        self.line_follow_auto_ctrl_flag = False
        self.line_follow_pause_flag = False
        self.line_there_last_time = 0
        self.line_xy_offset_list = []
        self.line_detection_flag = False
        self.line_follow_color = rm_define.line_follow_color_red
        self.marker_detection_color = rm_define.marker_detection_color_red
        self.line_follow_exception = False
        self.line_lost_has_ctrl = False
        self.cur_marker_with_follow_line_list = []
        self.is_line_there = False
        self.line_info = None
        self.line_intersection = None
        self.line_follow_front_speed = rm_define.line_follow_front_speed_default
        self.line_lost_time_out = (
            rm_define.line_follow_line_lost_distance_default
            / self.line_follow_front_speed
        )
        self.gimbal_ctrl = GimbalCtrl(event_client)

        self.vision_detection_data = {
            "data": {
                rm_define.vision_detection_head_shoulder_type: 0,
                rm_define.vision_detection_people_type: 0,
                rm_define.vision_detection_pose_type: 0,
                rm_define.vision_detection_auto_aim_type: 0,
                rm_define.vision_detection_line_type: 0,
                rm_define.vision_detection_marker_type: 0,
                rm_define.vision_detection_people_follow_type: 0,
                rm_define.vision_detection_car_type: 0,
            },
            "time": {
                rm_define.vision_detection_head_shoulder_type: 0,
                rm_define.vision_detection_people_type: 0,
                rm_define.vision_detection_pose_type: 0,
                rm_define.vision_detection_auto_aim_type: 0,
                rm_define.vision_detection_line_type: 0,
                rm_define.vision_detection_marker_type: 0,
                rm_define.vision_detection_people_follow_type: 0,
                rm_define.vision_detection_car_type: 0,
            },
        }

    def init(self):
        self.marker_detection_color_set(rm_define.marker_detection_color_red)

    @event_register
    def __enable_detection(self, vision_func):
        logger.info("VISION_CTRL: enable detection, vision func is %s" % vision_func)
        CHECK_VALUE_IN_ENUM_LIST(
            vision_func,
            **{
                "rm_define.vision_detection_marker": rm_define.vision_detection_marker,
                "rm_define.vision_detection_pose": rm_define.vision_detection_pose,
                "rm_define.vision_detection_people": rm_define.vision_detection_people,
                "rm_define.vision_detection_car": rm_define.vision_detection_car,
                "rm_define.vision_detection_line": rm_define.vision_detection_line,
                "rm_define.vision_detection_head_shoulder": rm_define.vision_detection_head_shoulder,
                "rm_define.vision_detection_auto_aim": rm_define.vision_detection_auto_aim,
                "rm_define.vision_detection_people_follow": rm_define.vision_detection_people_follow,
            }
        )
        result, curr_func = self.vision.vision_get_sdk_func()
        logger.info("VISION_CTRL: cur enable detection, vision func is %s" % curr_func)
        if result == rm_define.DUSS_SUCCESS:
            if vision_func == (curr_func & vision_func):
                self.vision.recognition_event_register(self.recognition_event_process)
                return get_task_dict(
                    None, "action_immediate", (vision_func), result=result
                )
            curr_func = curr_func | vision_func

            # check priority
            """
            detection_mask = 1
            default_cur_high_detection_item = {'priority':rm_define.vision_priority_lowest, 'detection_mask':0}
            cur_high_detection_items = {10:dict(default_cur_high_detection_item), 8:dict(default_cur_high_detection_item), 4:dict(default_cur_high_detection_item)}
            if vision_func & rm_define.vision_detection_line == rm_define.vision_detection_line:
                VisionCtrl.detection_enable_priority[rm_define.vision_detection_marker]['priority'] = rm_define.vision_priority_highest
            while detection_mask <= curr_func:
                if detection_mask & curr_func == detection_mask:
                    core_num = VisionCtrl.detection_enable_priority[detection_mask]['core_num']
                    priority = VisionCtrl.detection_enable_priority[detection_mask]['priority']
                    if priority < cur_high_detection_items[core_num]['priority']:
                        cur_high_detection_items[core_num]['priority'] = priority
                        cur_high_detection_items[core_num]['detection_mask'] = detection_mask
                    elif priority == cur_high_detection_items[core_num]['priority']:
                        cur_high_detection_items[core_num]['detection_mask'] = cur_high_detection_items[core_num]['detection_mask'] | detection_mask
                detection_mask = detection_mask << 1
            curr_func = 0
            for detection_item in cur_high_detection_items.values():
                curr_func = curr_func | detection_item['detection_mask']
            """

            result = self.vision.vision_sdk_enable(curr_func)

            if result != rm_define.DUSS_SUCCESS:
                logger.info("VISION_CTRL: enable detection failue")
            else:
                logger.info("VISION_CTRL: enable detection success")
                self.vision.recognition_event_register(self.recognition_event_process)
                result, curr_func_enabled = self.vision.vision_get_sdk_func()
                if curr_func & curr_func_enabled != curr_func:
                    logger.error(
                        "VISION_CTRL: enable detection func %s failue, cur enable is %s"
                        % (curr_func, curr_func_enabled)
                    )
                    return get_task_dict(
                        None,
                        "action_immediate",
                        (vision_func),
                        result=rm_define.FAILURE,
                        err_code=rm_define.BLOCK_ERR_AI_REFUSED,
                    )
                logger.info(
                    "VISION_CTRL: cur enable detection, vision func is %s"
                    % curr_func_enabled
                )
        else:
            logger.info("VISION_CTRL: get cur vision sdk failue")
        return get_task_dict(None, "action_immediate", (vision_func), result=result)

    def __disable_detection(self, vision_func):
        logger.info("VISION_CTRL: disable detection, vision func is %s" % vision_func)
        result, curr_func = self.vision.vision_get_sdk_func()
        if result == rm_define.DUSS_SUCCESS:

            # recovery marker priority
            # if vision_func & rm_define.vision_detection_line == rm_define.vision_detection_line:
            #    VisionCtrl.detection_enable_priority[rm_define.vision_detection_marker]['priority'] = rm_define.vision_priority_marker

            self.vision.vision_sdk_enable(0)
            curr_func = curr_func & ~vision_func
            result = self.vision.vision_sdk_enable(curr_func)
            if result != rm_define.DUSS_SUCCESS:
                logger.info("VISION_CTRL: disable detection failue")
        else:
            logger.info("VISION_CTRL: get cur vision sdk failue")
        return result

    ## python and scratch ##
    def enable_detection(self, vision_func):
        result = get_result(self.__enable_detection(vision_func))
        return result

    ## python and scratch ##
    def disable_detection(self, vision_func):
        result = get_result(self.__disable_detection(vision_func))
        return result

    def recognition_event_process(self, event, msg):
        data = msg["data"]
        detection_type = data[0]
        num = data[8]
        if num > 0:
            status = data[1]
            if status != rm_define.detection_push_status_running:
                # TODO: detection not run, and will return
                pass
            # resetved = tools.byte_to_uint32(data[2:6])
            # error_code = tools.byte_to_uint16(data[6:8])
            callback_data = None
            if detection_type in VisionCtrl.event_detection_type_dict.keys():
                callback_data = []
                for i in range(1, num + 1):
                    callback_data.append(
                        self.callback_data_pack(
                            data[9 + 20 * (i - 1) : i * 20 + 9], detection_type
                        )
                    )

                if len(VisionCtrl.event_detection_type_dict[detection_type]) > 1:
                    callback_data_cp = callback_data.copy()
                    for item in callback_data_cp:
                        if item.distance < self.marker_detection_distance:
                            func_str = self.get_marker_xx_all_func_str(
                                detection_type, item.info
                            )
                            if func_str != None:
                                self.condition_wait_list_append(func_str)
                            if (
                                item.info
                                in VisionCtrl.event_detection_type_dict[
                                    detection_type
                                ].keys()
                            ):
                                func_str = VisionCtrl.event_detection_type_dict[
                                    detection_type
                                ][item.info]
                                self.condition_wait_list_append(func_str)
                                logger.info(
                                    "VISION_CTRL: %s wait to wakeup" % (func_str,)
                                )
                            else:
                                logger.error(
                                    "VISION_CTRL: detection_typs is %s, info is %s, not support "
                                    % (detection_type, item.info)
                                )
                        else:
                            logger.info(
                                "VISION_CTRL: %s out of distance, filtered distance is %s, cur detection distance is %s"
                                % (
                                    item.info,
                                    self.marker_detection_distance,
                                    item.distance,
                                )
                            )
                            callback_data.remove(item)

                    if len(callback_data) > 0:
                        func_str = VisionCtrl.event_detection_type_dict[detection_type][
                            rm_define.detection_all_default
                        ]
                        self.condition_wait_list_append(func_str)
                else:
                    func_str = VisionCtrl.event_detection_type_dict[detection_type][
                        rm_define.detection_all_default
                    ]
                    self.condition_wait_list_append(func_str)

                self.marker_detection_result_info = callback_data

                if self.vision_detection_data_mutex.acquire(timeout=0.1):
                    self.vision_detection_data["data"][detection_type] = callback_data
                    self.vision_detection_data["time"][detection_type] = time.time()
                    self.vision_detection_data_mutex.release()

                """
                if len(callback_data) > 0:
                    self.update_line_follow_exception_flag_by_detection_type(detection_type)
                """
                if detection_type == rm_define.vision_detection_line_type:
                    # self.line_detection_process(callback_data)
                    pass
                elif len(VisionCtrl.event_detection_type_dict[detection_type]) == 1:
                    if (
                        VisionCtrl.event_detection_type_dict[detection_type][
                            rm_define.detection_all_default
                        ]
                        in self.recognition_event_cb
                    ):
                        func_str = VisionCtrl.event_detection_type_dict[detection_type][
                            rm_define.detection_all_default
                        ]

                        if func_str in self.wait_event_callback_need_data_set:
                            self.event_client.event_come_to_process(
                                func_str, callback_data
                            )
                        else:
                            self.event_client.event_come_to_process(func_str, None)

                    logger.info(
                        "VISION_CTRL: trigger %s"
                        % VisionCtrl.event_detection_type_dict[detection_type][0]
                    )
                elif len(VisionCtrl.event_detection_type_dict[detection_type]) > 1:
                    if (
                        VisionCtrl.event_detection_type_dict[detection_type][
                            rm_define.detection_all_default
                        ]
                        in self.recognition_event_cb
                    ):
                        func_str = VisionCtrl.event_detection_type_dict[detection_type][
                            rm_define.detection_all_default
                        ]
                        if func_str in self.wait_event_callback_need_data_set:
                            self.event_client.event_come_to_process(
                                func_str, callback_data
                            )
                        else:
                            self.event_client.event_come_to_process(func_str, None)

                        logger.info(
                            "VISION_CTRL: trigger %s"
                            % VisionCtrl.event_detection_type_dict[detection_type][
                                rm_define.detection_all_default
                            ]
                        )
                    kind = set()
                    kind_callback_data = (
                        {}
                    )  # multiple quantitiles of same type. key:kind, value:image list

                    if len(callback_data) != 0:
                        for data_t in callback_data:
                            kind.add(data_t.info)
                            if data_t.info in kind_callback_data.keys():
                                kind_callback_data[data_t.info].append(data_t)
                            else:
                                kind_callback_data[data_t.info] = [data_t]

                    for index in kind:
                        if (
                            index
                            in VisionCtrl.event_detection_type_dict[
                                detection_type
                            ].keys()
                            and VisionCtrl.event_detection_type_dict[detection_type][
                                index
                            ]
                            in self.recognition_event_cb
                        ):
                            func_str = VisionCtrl.event_detection_type_dict[
                                detection_type
                            ][index]

                            if func_str in self.wait_event_callback_need_data_set:
                                self.event_client.event_come_to_process(
                                    func_str, kind_callback_data[index]
                                )
                            else:
                                self.event_client.event_come_to_process(func_str, None)

                            logger.info(
                                "VISION_CTRL: trigger %s"
                                % VisionCtrl.event_detection_type_dict[detection_type][
                                    index
                                ]
                            )

                        # marker_xx_all
                        func_str = self.get_marker_xx_all_func_str(
                            detection_type, index
                        )

                        if func_str in self.recognition_event_cb:
                            self.event_client.event_come_to_process(
                                func_str, kind_callback_data[index]
                            )
                else:
                    logger.warn(
                        "VISION_CTRL: not support detection, cur msg detectino is %s, cur support detection is %s"
                        % (detection_type, VisionCtrl.event_detection_type_dict)
                    )
            else:
                logger.warn(
                    "VISION_CTRL: not support detection, cur msg detectino is %s, cur support detection is %s"
                    % (detection_type, VisionCtrl.event_detection_type_dict)
                )

    def callback_data_pack(self, rect_data_t, type_t):
        image_info = VisionCtrl.ImageInfo()
        image_info.type = type_t
        image_info.pos.x = tools.byte_to_float(rect_data_t[0:4])
        image_info.pos.y = tools.byte_to_float(rect_data_t[4:8])
        image_info.size.w = tools.byte_to_float(rect_data_t[8:12])
        image_info.size.h = tools.byte_to_float(rect_data_t[12:16])
        image_info.info = tools.byte_to_uint16(rect_data_t[16:18])
        image_info.distance = tools.byte_to_uint16(rect_data_t[18:20])
        return image_info

    def recognition_event_stop(self):
        result = True
        self.marker_detection_color_set(rm_define.marker_detection_color_red)
        result = self.vision.vision_sdk_enable(0)
        self.vision.recognition_event_unregister()
        logger.info("VISION_CTRL: disable all vision detection")
        return result

    def register_event(self, func_dict):
        function_mask = 0
        for event_str in VisionCtrl.event_detection_mask_dict.keys():
            if event_str in func_dict.keys():
                self.event_client.event_callback_register(
                    event_str, func_dict[event_str]
                )
                self.recognition_event_cb.append(event_str)
                logger.info("VISION_CTRL: register event %s" % (event_str))
                function_mask = (
                    function_mask | VisionCtrl.event_detection_mask_dict[event_str]
                )
                if (
                    event_str
                    in VisionCtrl.event_detection_callback_need_data_dict.keys()
                    and VisionCtrl.event_detection_callback_need_data_dict[event_str]
                ):
                    self.wait_event_callback_need_data_set_add(event_str)

        logger.info(
            "VISION_CTRL: cur register vision event callback is %s"
            % self.recognition_event_cb
        )

        """
        if len(self.recognition_event_cb) > 0:
            self.enable_detection(function_mask)
        """

    def register_event_test(self, func_dict):
        for event_str in VisionCtrl.event_detection_mask_dict.keys():
            if event_str in func_dict.keys():
                self.event_client.event_callback_register(
                    event_str, func_dict[event_str]
                )
                self.recognition_event_cb.append(event_str)
                self.wait_event_callback_need_data_set_add(event_str)

        if len(self.recognition_event_cb) > 0:
            self.vision.recognition_event_register(self.recognition_event_process)

        logger.info(
            "VISION_CTRL: cur register vision event callback is %s"
            % self.recognition_event_cb
        )

    def condition_wait_list_append(self, func_str):
        self.condition_mutex.acquire()
        self._wakeup_condition_waiting(func_str)
        self.condition_event_time[func_str] = time.time()
        self.condition_event_table[func_str] = True
        self.condition_mutex.release()

    def cond_wait(self, func_str):
        logger.info("VISION_CTRL: cond wait, func_str is %s" % (func_str))
        # self.enable_detection(VisionCtrl.event_detection_mask_dict[func_str])

        condition_wait_event = threading.Event()
        self._cond_wait_register(func_str, condition_wait_event)
        self.robot_sleep(3600 * 1000, self.check_cond_wait_event, func_str)
        self._cond_wait_unregister(func_str)

    def check_condition(self, func_str):
        logger.info("VISION_CTRL: check condition, func_str is %s" % (func_str))
        # self.enable_detection(VisionCtrl.event_detection_mask_dict[func_str])
        self.condition_mutex.acquire()

        event_happen = False
        if func_str in self.condition_event_table.keys():
            curr_time = time.time()
            if curr_time - self.condition_event_time[func_str] > 1.0:
                self.condition_event_table[func_str] = False
            event_happen = self.condition_event_table[func_str]
            # self.condition_event_table[func_str] = False
        self.condition_mutex.release()
        return event_happen

    def check_cond_wait_event(self, func_str):
        logger.info("VISION_CTRL: check cond wait event, func_str is %s" % (func_str))
        event_state = False
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            event_state = self.condition_wait_event_list[func_str].isSet()
        self.condition_mutex.release()
        return event_state

    def _cond_wait_register(self, func_str, wait_event):
        self.condition_mutex.acquire()
        wait_event.clear()
        self.condition_wait_event_list[func_str] = wait_event
        self.condition_mutex.release()

    def _cond_wait_unregister(self, func_str):
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list.pop(func_str)
        self.condition_mutex.release()

    def _wakeup_condition_waiting(self, func_str):
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list[func_str].set()

    def wait_event_callback_need_data_set_add(self, func_str):
        self.wait_event_callback_need_data_set.add(func_str)

    def get_marker_xx_all_func_str(self, detection_type, index):
        if detection_type == rm_define.vision_detection_marker_type:
            if (
                index >= rm_define.marker_number_zero
                and index <= rm_define.marker_number_nine
            ):
                return VisionCtrl.event_detection_type_dict[detection_type][
                    rm_define.marker_number_all
                ]
            elif (
                index >= rm_define.marker_letter_A
                and index <= rm_define.marker_letter_Z
            ):
                return VisionCtrl.event_detection_type_dict[detection_type][
                    rm_define.marker_letter_all
                ]
            elif (
                index >= rm_define.marker_trans_left
                and index <= rm_define.marker_trans_backward
            ):
                return VisionCtrl.event_detection_type_dict[detection_type][
                    rm_define.marker_trans_all
                ]
            else:
                return None
        else:
            return None

    def set_marker_detection_distance(self, distance):
        CHECK_VALUE_RANGE_AND_TYPE(distance, 0, 5, int, float)
        self.marker_detection_distance = distance * 100
        return True

    def line_detection_process(self, data):
        num = len(data)
        output = []
        info = None
        for item in data:
            x = item.pos.x
            y = item.pos.y
            info = item.info
            output.append((x, y))

        # line lost
        if num > 1:
            self.line_info = info & 0x0F
            self.is_line_there = not (
                abs(output[0][1]) < 0.0001 and abs(output[1][1]) < 0.0001
            )

            if self.line_info == 2:
                self.line_intersection = rm_define.line_intersection_Y
            elif self.line_info == 3:
                self.line_intersection = rm_define.line_intersection_X
            else:
                self.line_intersection = None

        # if num > 0:
        #    self.is_line_there = not (self.line_intersection == 0)

        self.line_xy_offset_list = output

        if self.is_line_there:
            self.line_there_last_time = time.time()
            self.line_lost_first_chassis_stop_flag = True
            self.line_lost_has_ctrl = False
        else:
            cur_time = time.time()
            if cur_time - self.line_there_last_time > self.line_lost_time_out:
                if (
                    self.line_lost_first_chassis_stop_flag
                    and not self.line_lost_has_ctrl
                    and self.line_follow_auto_ctrl_flag
                ):
                    self.line_follow_chassis_ctrl(rm_define.line_follow_chassis_stop)
                    self.line_lost_first_chassis_stop_flag = False
            else:
                self.is_line_there = True

        if not self.is_line_there or self.line_intersection:
            self.line_follow_exception = True

    def start_line_detection(self):
        logger.info("VISION_CTRL: start line detection")
        result = self.enable_detection(
            rm_define.vision_detection_line | rm_define.vision_detection_marker
        )
        if result == rm_define.DUSS_SUCCESS:
            logger.info("VISION_CTRL: start line detection success")
            self.line_detection_flag = True
        else:
            logger.warn("VISION_CTRL: start line detection failue")
            result = self.stop_line_follow()
            if result == rm_define.DUSS_SUCCESS:
                logger.info("VISION_CTRL: stop line detection success")
            else:
                logger.warn("VISION_CTRL: stop line detection failue")
        return result

    @event_register
    def start_line_follow_until_exception(self):
        logger.info(
            "VISION_CTRL: start line follow until exception,  the line color is %s"
            % color
        )
        self.cur_marker_with_follow_line_list = []
        self.line_intersection = None
        if self.line_detection_flag == False:
            result = self.start_line_detection()
            if result == rm_define.DUSS_SUCCESS:
                if (
                    self.line_follow_auto_ctrl_thread == None
                    or not self.line_follow_auto_ctrl_thread.isAlive()
                ):
                    self.line_follow_auto_ctrl_thread = threading.Thread(
                        target=self.line_follow_auto_ctrl
                    )
                    self.line_follow_auto_ctrl_thread.start()
                    self.line_follow_auto_ctrl_flag = True
            self.line_detection_flag = True
        self.vision.detection_attr_set(1, self.line_follow_color)
        self.line_follow_pause_flag = False
        while True:
            self._wait(0.2)
            if self.line_follow_exception:
                break
        self.line_follow_exception = False
        logger.info("VISION_CTRL: line follow Exception")
        return get_task_dict(None, "action_immediate", (self.line_follow_color), {})

    def line_follow_auto_ctrl(self):
        while self.line_follow_auto_ctrl_flag:
            if not self.line_follow_pause_flag and self.is_line_there:
                input_error = self.get_line_detection_deviation_point()[0]
                gimbal_yaw_ctrl = {
                    "amount": input_error,
                    "kp": 40,
                    "ki": 0,
                    "kd": 0.5,
                    "max_speed": 1500,
                }
                gimbal_pitch_ctrl = {
                    "amount": 0,
                    "kp": 0,
                    "ki": 0,
                    "kd": 0,
                    "max_speed": 0,
                }
                chassis_x_ctrl = {
                    "amount": self.line_follow_front_speed,
                    "kp": 1,
                    "ki": 0,
                    "kd": 0,
                    "max_speed": 2,
                }
                chassis_y_ctrl = {
                    "amount": 0,
                    "kp": 0.1,
                    "ki": 0,
                    "kd": 0,
                    "max_speed": 1,
                }
                chassis_yaw_ctrl = {
                    "amount": 0,
                    "kp": 1,
                    "ki": 0,
                    "kd": 0,
                    "max_speed": 30,
                }
                self.set_line_follow_param_gimbal(
                    rm_define.robot_mode_chassis_follow,
                    gimbal_yaw_ctrl,
                    gimbal_pitch_ctrl,
                )
                self.set_line_follow_param_chassis(
                    rm_define.robot_mode_chassis_follow,
                    chassis_x_ctrl,
                    chassis_y_ctrl,
                    chassis_yaw_ctrl,
                )
            time.sleep(0.03)
        logger.info("VISION_CTRL: exit line follow auto ctrl")

    @event_register
    def _wait(self, s):
        time.sleep(s)
        return get_task_dict(None, "action_immediate", (), {})

    @event_register
    def stop_line_follow(self):
        logger.info("VISION_CTRL: stop line follow")
        result = rm_define.DUSS_SUCCESS
        if self.line_detection_flag == True:
            self.line_follow_auto_ctrl_flag = False
            if self.line_follow_auto_ctrl_thread != None:
                self.line_follow_auto_ctrl_thread.join()
            self.line_follow_chassis_ctrl(rm_define.line_follow_chassis_stop)
            result = self.disable_detection(
                rm_define.vision_detection_line | rm_define.vision_detection_marker
            )
            self.line_detection_flag = False
        return get_task_dict(None, "action_immediate", (), {"result": result})

    def set_line_detection_params(
        self, ctrl_object, ctrl_amount, ctrl_max_speed, kp, ki=0, kd=0
    ):
        self.vision.ctrl_param_set(ctrl_object, ctrl_amount, ctrl_max_speed, kp, ki, kd)

    def set_line_follow_param_chassis(
        self, robot_mode, x_ctrl_dict, y_ctrl_dict, yaw_ctrl_dict
    ):
        self.vision.chassis_ctrl_param_set(
            robot_mode, x_ctrl_dict, y_ctrl_dict, yaw_ctrl_dict
        )

    def set_line_follow_param_gimbal(self, robot_mode, yaw_ctrl_dict, pitch_ctrl_dict):
        self.vision.gimbal_ctrl_param_set(robot_mode, yaw_ctrl_dict, pitch_ctrl_dict)

    def set_line_follow_speed(self, speed):
        logger.info("VISION_CTRL: set line follow speed, the speed is %f" % speed)
        self.line_follow_front_speed = speed
        self.line_lost_time_out = (
            rm_define.line_follow_line_lost_distance_default
            / self.line_follow_front_speed
        )

    @event_register
    def line_follow_chassis_ctrl(self, direction):
        logger.info(
            "VISION_CTRL: line follow chassis ctrl, the direction is %s" % direction
        )
        self.line_lost_has_ctrl = True
        if direction == rm_define.line_follow_chassis_stop:
            self.line_follow_pause_flag = True
            gimbal_yaw_ctrl = {
                "amount": 0,
                "kp": 40,
                "ki": 0,
                "kd": 0.5,
                "max_speed": 1500,
            }
            gimbal_pitch_ctrl = {"amount": 0, "kp": 0, "ki": 0, "kd": 0, "max_speed": 0}
            chassis_x_ctrl = {"amount": 0, "kp": 1, "ki": 0, "kd": 0, "max_speed": 2}
            chassis_y_ctrl = {"amount": 0, "kp": 0.1, "ki": 0, "kd": 0, "max_speed": 1}
            chassis_yaw_ctrl = {"amount": 0, "kp": 1, "ki": 0, "kd": 0, "max_speed": 30}
            self.set_line_follow_param_gimbal(
                rm_define.robot_mode_chassis_follow, gimbal_yaw_ctrl, gimbal_pitch_ctrl
            )
            self.set_line_follow_param_chassis(
                rm_define.robot_mode_chassis_follow,
                chassis_x_ctrl,
                chassis_y_ctrl,
                chassis_yaw_ctrl,
            )
        elif (
            direction == rm_define.line_follow_chassis_right
            or direction == rm_define.line_follow_chassis_left
            or direction == rm_define.line_follow_chassis_front
        ):
            self.line_follow_line_choice(direction)
        elif direction == rm_define.line_follow_chassis_turn_around:
            self.line_follow_chassis_ctrl(rm_define.line_follow_chassis_stop)
            self.gimbal_ctrl.yaw_ctrl(-180)
        else:  # not support
            pass
        return get_task_dict(None, "action_immediate", (direction,), {})

    def line_follow_color_set(self, color):
        CHECK_VALUE_IN_ENUM_LIST(
            color,
            **{
                "rm_define.line_follow_color_red": rm_define.line_follow_color_red,
                "rm_define.line_follow_color_blue": rm_define.line_follow_color_blue,
                "rm_define.line_follow_color_green": rm_define.line_follow_color_green,
            }
        )
        self.line_follow_color = color
        self.vision.detection_attr_set(1, self.line_follow_color)

    def marker_detection_color_set(self, color):
        CHECK_VALUE_IN_ENUM_LIST(
            color,
            **{
                "rm_define.marker_detection_color_red": rm_define.marker_detection_color_red,
                "rm_define.marker_detection_color_blue": rm_define.marker_detection_color_blue,
                "rm_define.marker_detection_color_green": rm_define.marker_detection_color_green,
            }
        )
        self.marker_detection_color = color
        self.vision.detection_attr_set(2, self.marker_detection_color)

    def line_follow_line_choice(self, direction):
        if direction == rm_define.line_follow_chassis_front:
            return self.vision.detection_attr_set(2, 1)
        elif direction == rm_define.line_follow_chassis_left:
            return self.vision.detection_attr_set(2, 2)
        elif direction == rm_define.line_follow_chassis_right:
            return self.vision.detection_attr_set(2, 3)

    def get_line_detection_deviation(self):
        xy_offset_near = self.get_line_detection_deviation_point(
            rm_define.line_detection_near_point
        )
        out_x = xy_offset_near[0]
        out_y = xy_offset_near[1]
        return out_x

    def get_line_detection_deviation_point(
        self, point=rm_define.line_detection_near_point
    ):
        xy_offset_near = (0, 0)
        xy_offset_middle = (0, 0)
        xy_offset_far = (0, 0)
        xy_offset_all = self.line_xy_offset_list
        if len(xy_offset_all) == 1:
            xy_offset_near = xy_offset_middle = xy_offset_far = xy_offset_all[0]
        elif len(xy_offset_all) == 3:
            xy_offset_near = xy_offset_all[0]
            xy_offset_middle = xy_offset_all[1]
            xy_offset_far = xy_offset_all[2]

        if point == rm_define.line_detection_far_point:
            return xy_offset_far
        elif point == rm_define.line_detection_middle_point:
            return xy_offset_middle
        else:
            return xy_offset_near

    def is_cur_line_follow_exception(self, ex):
        logger.info(
            "VISION_CTRL: is cur line follow exception, the check exception is "
            + str(ex)
        )
        return (ex is self.is_line_there) or (ex == self.line_intersection)

    def update_line_follow_exception_flag_by_detection_type(self, detection_type):
        if detection_type in VisionCtrl.line_follow_exception_detection_type_list:
            self.line_follow_exception = True

    @event_register
    def detect_marker_and_aim(self, marker_id):
        CHECK_VALUE_RANGE_AND_TYPE(marker_id, 0, 45, int)
        begin_time = time.time()
        run_time = 0
        self.gimbal_ctrl.stop()
        while run_time < 5:  # 2s time out
            result_info = self.marker_detection_result_info
            offset_pitch = 0
            offset_yaw = 0
            distance = 0
            for item in result_info:
                if (
                    item.type == rm_define.vision_detection_marker_type
                    and item.info == marker_id
                ):
                    offset_pitch = (0.53 - item.pos.y) * 54
                    offset_yaw = (item.pos.x - 0.5) * 96
                    if abs(offset_pitch) < 1 and abs(offset_yaw) < 1:  # 0.1°
                        gimbal_yaw_ctrl = {
                            "amount": 0,
                            "kp": 0,
                            "ki": 0,
                            "kd": 0,
                            "max_speed": 0,
                        }
                        gimbal_pitch_ctrl = {
                            "amount": 0,
                            "kp": 0,
                            "ki": 0,
                            "kd": 0,
                            "max_speed": 0,
                        }
                        self.set_line_follow_param_gimbal(
                            rm_define.robot_mode_chassis_follow,
                            gimbal_yaw_ctrl,
                            gimbal_pitch_ctrl,
                        )
                        return get_task_dict(None, "action_immediate", (marker_id,), {})
                    gimbal_yaw_ctrl = {
                        "amount": item.pos.x - 0.5,
                        "kp": 1800,
                        "ki": 0,
                        "kd": 1.5,
                        "max_speed": 250,
                    }
                    gimbal_pitch_ctrl = {
                        "amount": 0.53 - item.pos.y,
                        "kp": 800,
                        "ki": 0,
                        "kd": 5.0,
                        "max_speed": 300,
                    }
                    self.set_line_follow_param_gimbal(
                        rm_define.robot_mode_chassis_follow,
                        gimbal_yaw_ctrl,
                        gimbal_pitch_ctrl,
                    )
                    break
                else:
                    logger.info("NO marker to aim, marker_id %s!!" % marker_id)
            time.sleep(0.04)
            run_time = time.time() - begin_time
        gimbal_yaw_ctrl = {"amount": 0, "kp": 0, "ki": 0, "kd": 0, "max_speed": 0}
        gimbal_pitch_ctrl = {"amount": 0, "kp": 0, "ki": 0, "kd": 0, "max_speed": 0}
        self.set_line_follow_param_gimbal(
            rm_define.robot_mode_chassis_follow, gimbal_yaw_ctrl, gimbal_pitch_ctrl
        )
        return get_task_dict(None, "action_immediate", (marker_id,), {})

    def get_vision_detection_info(self, detection_type):
        output_list = [0]
        if detection_type == rm_define.vision_detection_line_type:
            output_list = [0, 0]
        if detection_type not in self.vision_detection_data["time"].keys():
            return output_list

        if not self.vision_detection_data_mutex.acquire(timeout=0.1):
            return output_list
        cur_time = self.vision_detection_data["time"][detection_type]
        data_list = self.vision_detection_data["data"][detection_type]
        self.vision_detection_data_mutex.release()

        if time.time() - cur_time > 1:
            return output_list
        if len(data_list) > 0:
            if (
                detection_type == rm_define.vision_detection_line_type
                and detection_type == data_list[0].type
            ):
                # [num, info, x1, y1, w1, h1, x2, y2, w2, h2...]
                num = len(data_list)
                if num > 10:
                    # get middle line info, every line combined with 10 points.
                    # to compatible cur scratch guide
                    pos = int((num / 10) / 2)
                    data_list = data_list[pos * 10 : (pos + 1) * 10]
                output_list = []
                output_list.append(len(data_list))
                output_list.append(data_list[0].info)
                for item in data_list:
                    output_list.append(item.pos.x)
                    output_list.append(item.pos.y)
                    output_list.append(item.size.w)
                    output_list.append(item.size.h)
            elif (
                detection_type == rm_define.vision_detection_people_type
                or detection_type == rm_define.vision_detection_car_type
                and detection_type == data_list[0].type
            ):
                # [num, x1, y1, w1, h1, x2, y2, w2, h2,...]
                output_list[0] = len(data_list)
                for item in data_list:
                    output_list.append(item.pos.x)
                    output_list.append(item.pos.y)
                    output_list.append(item.size.w)
                    output_list.append(item.size.h)
            elif detection_type == data_list[0].type:
                # [num, info1, x1, y1, w1, h1, info2, x2, y2, w2, h2,...]
                output_list[0] = len(data_list)
                for item in data_list:
                    output_list.append(item.info)
                    output_list.append(item.pos.x)
                    output_list.append(item.pos.y)
                    output_list.append(item.size.w)
                    output_list.append(item.size.h)
        return output_list

    def get_marker_detection_info(self):
        return self.get_vision_detection_info(rm_define.vision_detection_marker_type)

    def get_pose_detection_info(self):
        return self.get_vision_detection_info(rm_define.vision_detection_pose_type)

    def get_people_detection_info(self):
        return self.get_vision_detection_info(rm_define.vision_detection_people_type)

    def get_line_detection_info(self):
        return self.get_vision_detection_info(rm_define.vision_detection_line_type)

    def get_car_detection_info(self):
        return self.get_vision_detection_info(rm_define.vision_detection_car_type)

    ## python and scratch ##
    def get_env_brightness(self):
        return self.media_ctrl.get_camera_brightness()

    def stop(self):
        logger.info("VISION_CTRL: stop")
        self.finish = True
        self.recognition_event_stop()
        self.stop_line_follow()

    ## Pyhton API ##
    def set_detection_filter(self, **kw):
        if "line_color" in kw.keys():
            return get_result(self.line_follow_color_set(kw["line_color"]))
        elif "marker_distance" in kw.keys():
            return get_result(self.set_marker_detection_distance(kw["marker_distance"]))
        else:
            return False

    # USE SCRATCH API
    # def enable_detection(self, detection_type)

    # USE SCRATCH API
    # def disable_detection(self, detection_type)

    def get_detection_info(self, detection_type):
        output_list = 0
        if detection_type == rm_define.vision_detection_line_type:
            output_list = (0, 0)
        if detection_type not in self.vision_detection_data["time"].keys():
            return output_list

        if not self.vision_detection_data_mutex.acquire():
            return output_list
        cur_time = self.vision_detection_data["time"][detection_type]
        data_list = self.vision_detection_data["data"][detection_type]
        self.vision_detection_data_mutex.release()

        if time.time() - cur_time > 1:
            return output_list

        if len(data_list) > 0:
            if detection_type == data_list[0].type:
                return tuple(data_list)
            else:
                return output_list
        else:
            return output_list

    def get_mult_line_detection_info(self):
        output_list = [0]

        if not self.vision_detection_data_mutex.acquire():
            return output_list
        cur_time = self.vision_detection_data["time"][
            rm_define.vision_detection_line_type
        ]
        data_list = self.vision_detection_data["data"][
            rm_define.vision_detection_line_type
        ]
        self.vision_detection_data_mutex.release()

        if time.time() - cur_time > 1:
            return output_list
        if len(data_list) > 0:
            output_list = [int(len(data_list) / 10)]
            for item in data_list:
                output_list.append(item.pos.x)
                output_list.append(item.pos.y)
                output_list.append(item.size.w)
                output_list.append(item.size.h)
        return output_list


class MediaCtrl(RobotCtrlTool):
    event_detection_type_dict = {
        rm_define.sound_detection_applause_type: {
            rm_define.applause_all: "sound_recognized_applause_all",
            rm_define.applause_once: "sound_recognized_applause_once",
            rm_define.applause_twice: "sound_recognized_applause_twice",
            rm_define.applause_thrice: "sound_recognized_applause_thrice",
        }
    }

    # not really maskbit
    event_detection_mask_dict = {
        "sound_recognized_applause_all": rm_define.sound_detection_applause,
        "sound_recognized_applause_once": rm_define.sound_detection_applause,
        "sound_recognized_applause_twice": rm_define.sound_detection_applause,
        "sound_recognized_applause_thrice": rm_define.sound_detection_applause,
    }

    event_detection_callback_need_data_dict = {
        "sound_recognized_applause_all": True,
    }

    def __init__(self, event_client):
        super().__init__(event_client)
        self.media = rm_module.Media(event_client)
        self.event_client = event_client
        self.condition_mutex = threading.Lock()
        self.condition_event_table = {}
        self.condition_event_time = {}
        self.condition_wait_event_list = {}
        self.recording = False
        self.recognition = False

        self.recognition_event_cb = []
        self.wait_event_callback_need_data_set = set()

        self.recognition_applause = False

        self.sdk_event_push_callback = None
        self.sdk_applause_event_push_enable_flag = False

    @event_register
    def capture(self):
        logger.info("MEDIA_CTRL: capture")
        duss_result = rm_define.DUSS_SUCCESS
        if self.recording:
            duss_result = rm_define.DUSS_ERR_NOT_SUPPORT
            logger.info("MEDIA_CTRL: recording is running, can not capture")
        else:
            duss_result = self.media.set_camera_mode(0)
            duss_result = self.media.capture()
        err_code = 0
        if duss_result != rm_define.DUSS_SUCCESS:
            err_code = self.media.get_err_code()
            err_code = tools.get_block_err_code(rm_define.camera_id, err_code)
            return get_task_dict(
                None, "action_immediate", (), result=duss_result, err_code=err_code
            )
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def wait_event_callback_need_data_set_add(self, func_str):
        self.wait_event_callback_need_data_set.add(func_str)

    # called by event_client thread
    def recognition_push_process(self, event_client, msg):
        data = msg["data"]
        recog_type = data[0]
        recog_data = data[1]
        logger.info(
            "MEDIA_CTRL: recog_typ is %s, recog_data is %s" % (recog_type, recog_data)
        )
        if recog_type in MediaCtrl.event_detection_type_dict.keys():
            event = {}
            if self.sdk_event_push_callback:
                if self.sdk_applause_event_push_enable_flag:
                    event["applause"] = recog_data
                self.sdk_event_push_callback(event)
            func_str = self.event_detection_type_dict[recog_type][
                rm_define.detection_all_default
            ]
            logger.info("MEDIA_CTRL: recog_type func_str %s" % (func_str))
            self.condition_mutex.acquire()
            self._wakeup_condition_waiting(func_str)
            self.condition_event_time[func_str] = time.time()
            self.condition_event_table[func_str] = True
            self.condition_mutex.release()
            if func_str in self.recognition_event_cb:
                self.event_client.event_come_to_process(func_str, recog_data)
            if recog_data in MediaCtrl.event_detection_type_dict[recog_type].keys():
                func_str = self.event_detection_type_dict[recog_type][recog_data]
                logger.info("MEDIA_CTRL: recog_data func_str %s" % (func_str))
                self.condition_mutex.acquire()
                self._wakeup_condition_waiting(func_str)
                self.condition_event_time[func_str] = time.time()
                self.condition_event_table[func_str] = True
                self.condition_mutex.release()
                if func_str in self.recognition_event_cb:
                    if func_str in self.wait_event_callback_need_data_set:
                        self.event_client.event_come_to_process(func_str, recog_data)
                    else:
                        self.event_client.event_come_to_process(func_str)

    def register_event(self, func_dict):
        function_mask = set()
        for event_str in MediaCtrl.event_detection_mask_dict.keys():
            if event_str in func_dict.keys():
                self.event_client.event_callback_register(
                    event_str, func_dict[event_str]
                )
                self.recognition_event_cb.append(event_str)
                logger.info("MEDIA_CTRL: register event %s" % (event_str))
                function_mask.add(MediaCtrl.event_detection_mask_dict[event_str])
                if (
                    event_str
                    in MediaCtrl.event_detection_callback_need_data_dict.keys()
                    and MediaCtrl.event_detection_callback_need_data_dict[event_str]
                ):
                    self.wait_event_callback_need_data_set_add(event_str)

        logger.info(
            "MEDIA_CTRL: cur register vision event callback is %s"
            % self.recognition_event_cb
        )

        """
        if len(self.recognition_event_cb) > 0:
            for mask in function_mask:
                self.enable_sound_recognition(mask)
        """

    def sdk_event_push_enable_flag_set(self, applause_flag=None, reserve=None):
        if applause_flag == "on":
            self.sdk_applause_event_push_enable_flag = True
            return self.enable_sound_recognition(rm_define.sound_detection_applause)
        elif applause_flag == "off":
            self.sdk_applause_event_push_enable_flag = False
            return self.enable_sound_recognition(rm_define.sound_detection_applause)
        else:
            return False

    def sdk_event_push_callback_register(self, cb):
        if callable(cb):
            self.sdk_event_push_callback = cb

    @event_register
    def enable_sound_recognition(self, func_mask):
        CHECK_VALUE_IN_ENUM_LIST(
            func_mask,
            **{"rm_define.sound_detection_applause": rm_define.sound_detection_applause}
        )
        self.media.recognition_push_register(self.recognition_push_process)
        result = self.media.enable_sound_recognition(1, func_mask)
        if result != rm_define.DUSS_SUCCESS:
            self.media.recognition_push_unregister()
        else:
            self.recognition_applause = True
        logger.info(
            "MEDIA_CTRL: enable sound recognition 0x%x, result is %s"
            % (func_mask, result)
        )
        return get_task_dict(None, "action_immediate", (func_mask), result=result)

    @event_register
    def disable_sound_recognition(self, func_mask):
        CHECK_VALUE_IN_ENUM_LIST(
            func_mask,
            **{"rm_define.sound_detection_applause": rm_define.sound_detection_applause}
        )
        result = self.media.enable_sound_recognition(0, func_mask)
        if result == rm_define.DUSS_SUCCESS:
            self.recognition_applause = False
        logger.info(
            "MEDIA_CTRL: disable sound recognition 0x%x, result is %s"
            % (func_mask, result)
        )
        return get_task_dict(None, "action_immediate", (func_mask), result=result)

    @event_register
    def disable_all_sound_recognition(self):
        result = rm_define.DUSS_SUCCESS
        if self.recognition_applause == True:
            result = self.media.enable_sound_recognition(
                0, rm_define.sound_detection_applause
            )
        logger.info("MEDIA_CTRL: disable all sound recognition result is %s" % (result))
        return get_task_dict(None, "action_immediate", (), result=result)

    def cond_wait(self, func_str):
        logger.info("MEDIA_CTRL: cond wait, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(
            func_str,
            **{
                "rm_define.cond_sound_recognized_applause_twice": rm_define.cond_sound_recognized_applause_twice,
                "rm_define.cond_sound_recognized_applause_thrice": rm_define.cond_sound_recognized_applause_thrice,
            }
        )
        # self.enable_sound_recognition(MediaCtrl.event_detection_mask_dict[func_str])
        condition_wait_event = threading.Event()
        self._cond_wait_register(func_str, condition_wait_event)
        self.robot_sleep(3600 * 1000, self.check_cond_wait_event, func_str)
        self._cond_wait_unregister(func_str)

    def check_condition(self, func_str):
        logger.info("MEDIA_CTRL: check condition, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(
            func_str,
            **{
                "rm_define.cond_sound_recognized_applause_twice": rm_define.cond_sound_recognized_applause_twice,
                "rm_define.cond_sound_recognized_applause_thrice": rm_define.cond_sound_recognized_applause_thrice,
            }
        )
        # self.enable_sound_recognition(MediaCtrl.event_detection_mask_dict[func_str])
        self.condition_mutex.acquire()
        event_happen = False
        if func_str in self.condition_event_table.keys():
            curr_time = time.time()
            if curr_time - self.condition_event_time[func_str] > 1.0:
                self.condition_event_table[func_str] = False
            event_happen = self.condition_event_table[func_str]
            self.condition_event_table[func_str] = False
        self.condition_mutex.release()
        return event_happen

    def check_cond_wait_event(self, func_str):
        logger.info("MEDIA_CTRL: check cond wait event, func_str is %s" % (func_str))
        event_state = False
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            event_state = self.condition_wait_event_list[func_str].isSet()
        self.condition_mutex.release()
        return event_state

    def _cond_wait_register(self, func_str, wait_event):
        self.condition_mutex.acquire()
        wait_event.clear()
        self.condition_wait_event_list[func_str] = wait_event
        self.condition_mutex.release()

    def _cond_wait_unregister(self, func_str):
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list.pop(func_str)
        self.condition_mutex.release()

    def _wakeup_condition_waiting(self, func_str):
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list[func_str].set()

    def check_condition(self, recog_type):
        CHECK_VALUE_IN_ENUM_LIST(
            recog_type,
            **{
                "rm_define.cond_sound_recognized_applause_twice": rm_define.cond_sound_recognized_applause_twice,
                "rm_define.cond_sound_recognized_applause_thrice": rm_define.cond_sound_recognized_applause_thrice,
            }
        )
        self.condition_mutex.acquire()
        event_happen = False
        if recog_type in self.condition_event_table.keys():
            curr_time = time.time()
            if curr_time - self.condition_event_time[recog_type] > 1.0:
                self.condition_event_table[recog_type] = False
            event_happen = self.condition_event_table[recog_type]
            self.condition_event_table[recog_type] = False
        self.condition_mutex.release()
        logger.info(
            "MEDIA_CTRL: check condition, recog_type is %s result is %s"
            % (recog_type, event_happen)
        )
        return event_happen

    @event_register
    def record(self, ctrl):
        logger.info("MEDIA_CTRL: record, ctrl is %s" % (ctrl))
        duss_result = rm_define.DUSS_SUCCESS
        if ctrl == 1:
            duss_result = self.media.set_camera_mode(1)
            self.recording = True
        else:
            self.recording = False
        duss_result = self.media.record(ctrl)
        if duss_result == rm_define.DUSS_SUCCESS:
            return get_task_dict(None, "action_immediate", (ctrl), result=duss_result)
        else:
            err_code = self.media.get_err_code()
            err_code = tools.get_block_err_code(rm_define.camera_id, err_code)
            return get_task_dict(
                None, "action_immediate", (ctrl), result=duss_result, err_code=err_code
            )

    @event_register
    def __play_sound(self, id, wait_for_complete_flag=False):
        logger.info(
            "MEDIA_CTRL: play sound, sound id is %s, wait_for_complete_flag is %s"
            % (id, wait_for_complete_flag)
        )
        if wait_for_complete_flag == True:
            self.interrupt_func_register(self.media.play_sound_task_stop)
            duss_result, identify = self.media.play_sound_task(id)
            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (id, wait_for_complete_flag), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (id, wait_for_complete_flag), result=duss_result
                )
        else:
            duss_result = self.media.play_sound(id)
            return get_task_dict(
                None,
                "action_immediate",
                (id, wait_for_complete_flag),
                result=duss_result,
            )

    @event_register
    def play_lib_sound(self, id, wait_for_complete_flag=False):
        logger.info(
            "MEDIA_CTRL: play lib sound, sound id is %s, wait_for_complete_flag is %s"
            % (id, wait_for_complete_flag)
        )
        if wait_for_complete_flag == True:
            self.interrupt_func_register(self.media.play_sound_task_stop)
            duss_result, identify = self.media.play_sound_task(0xF0 + id)
            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (id, wait_for_complete_flag), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (id, wait_for_complete_flag), result=duss_result
                )
        else:
            duss_result = self.media.play_sound(0xF0 + id)
            return get_task_dict(
                None,
                "action_immediate",
                (id, wait_for_complete_flag),
                result=duss_result,
            )

    @event_register
    def exposure_value_update(self, ev):
        CHECK_VALUE_IN_ENUM_LIST(
            ev,
            **{
                "rm_define.exposure_value_default": rm_define.exposure_value_default,
                "rm_define.exposure_value_large": rm_define.exposure_value_large,
                "rm_define.exposure_value_medium": rm_define.exposure_value_medium,
                "rm_define.exposure_value_small": rm_define.exposure_value_small,
            }
        )
        duss_result, resp = self.media.set_camera_ev(ev)
        return get_task_dict(None, "action_immediate", (ev), result=duss_result)

    @event_register
    def zoom_value_update(self, zv):
        zv = int(zv * 100)
        CHECK_VALUE_RANGE_AND_TYPE(
            zv, rm_define.zoom_value_min, rm_define.zoom_value_max, int
        )
        duss_result, resp = self.media.set_camera_zv(zv)
        return get_task_dict(None, "action_immediate", (zv), result=duss_result)

    def get_sight_bead_position(self):
        duss_result, resp = self.media.get_sight_bead_position()
        data = resp["data"][1:]
        x = tools.byte_to_int16(data[0:2])
        y = tools.byte_to_int16(data[2:4])
        x = x / 10000
        y = 1 - y / 10000
        return [x, y]

    def get_camera_brightness(self):
        duss_result, resp = self.media.get_camera_brightness()
        # TODO: corverted to brightness units
        bri = 0
        if duss_result == rm_define.DUSS_SUCCESS:
            bri = tools.byte_to_float(resp["data"][1:5])
        return bri

    @event_register
    def stop(self):
        logger.info("MEDIA_CTRL: stop")
        duss_result = rm_define.DUSS_SUCCESS
        if self.recording:
            self.record(0)
            logger.info("MEDIA_CTRL: stop recording")
        self.disable_all_sound_recognition()
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def exit(self):
        self.exposure_value_update(rm_define.exposure_value_default)
        self.zoom_value_update(1)

    ## Python API ##
    def play_sound(self, id, **kw):
        CHECK_VALUE_IN_ENUM_LIST(
            id,
            **{
                "rm_define.media_sound_attacked": rm_define.media_sound_attacked,
                "rm_define.media_sound_shoot": rm_define.media_sound_shoot,
                "rm_define.media_sound_scanning": rm_define.media_sound_scanning,
                "rm_define.media_sound_recognize_success": rm_define.media_sound_recognize_success,
                "rm_define.media_sound_gimbal_rotate": rm_define.media_sound_gimbal_rotate,
                "rm_define.media_sound_count_down": rm_define.media_sound_count_down,
                "rm_define.media_sound_solmization_1C": rm_define.media_sound_solmization_1C,
                "rm_define.media_sound_solmization_1CSharp": rm_define.media_sound_solmization_1CSharp,
                "rm_define.media_sound_solmization_1D": rm_define.media_sound_solmization_1D,
                "rm_define.media_sound_solmization_1DSharp": rm_define.media_sound_solmization_1DSharp,
                "rm_define.media_sound_solmization_1E": rm_define.media_sound_solmization_1E,
                "rm_define.media_sound_solmization_1F": rm_define.media_sound_solmization_1F,
                "rm_define.media_sound_solmization_1FSharp": rm_define.media_sound_solmization_1FSharp,
                "rm_define.media_sound_solmization_1G": rm_define.media_sound_solmization_1G,
                "rm_define.media_sound_solmization_1GSharp": rm_define.media_sound_solmization_1GSharp,
                "rm_define.media_sound_solmization_1A": rm_define.media_sound_solmization_1A,
                "rm_define.media_sound_solmization_1ASharp": rm_define.media_sound_solmization_1ASharp,
                "rm_define.media_sound_solmization_1B": rm_define.media_sound_solmization_1B,
                "rm_define.media_sound_solmization_2C": rm_define.media_sound_solmization_2C,
                "rm_define.media_sound_solmization_2CSharp": rm_define.media_sound_solmization_2CSharp,
                "rm_define.media_sound_solmization_2D": rm_define.media_sound_solmization_2D,
                "rm_define.media_sound_solmization_2DSharp": rm_define.media_sound_solmization_2DSharp,
                "rm_define.media_sound_solmization_2E": rm_define.media_sound_solmization_2E,
                "rm_define.media_sound_solmization_2F": rm_define.media_sound_solmization_2F,
                "rm_define.media_sound_solmization_2FSharp": rm_define.media_sound_solmization_2FSharp,
                "rm_define.media_sound_solmization_2G": rm_define.media_sound_solmization_2G,
                "rm_define.media_sound_solmization_2GSharp": rm_define.media_sound_solmization_2GSharp,
                "rm_define.media_sound_solmization_2A": rm_define.media_sound_solmization_2A,
                "rm_define.media_sound_solmization_2ASharp": rm_define.media_sound_solmization_2ASharp,
                "rm_define.media_sound_solmization_2B": rm_define.media_sound_solmization_2B,
                "rm_define.media_sound_solmization_3C": rm_define.media_sound_solmization_3C,
                "rm_define.media_sound_solmization_3CSharp": rm_define.media_sound_solmization_3CSharp,
                "rm_define.media_sound_solmization_3D": rm_define.media_sound_solmization_3D,
                "rm_define.media_sound_solmization_3DSharp": rm_define.media_sound_solmization_3DSharp,
                "rm_define.media_sound_solmization_3E": rm_define.media_sound_solmization_3E,
                "rm_define.media_sound_solmization_3F": rm_define.media_sound_solmization_3F,
                "rm_define.media_sound_solmization_3FSharp": rm_define.media_sound_solmization_3FSharp,
                "rm_define.media_sound_solmization_3G": rm_define.media_sound_solmization_3G,
                "rm_define.media_sound_solmization_3GSharp": rm_define.media_sound_solmization_3GSharp,
                "rm_define.media_sound_solmization_3A": rm_define.media_sound_solmization_3A,
                "rm_define.media_sound_solmization_3ASharp": rm_define.media_sound_solmization_3ASharp,
                "rm_define.media_sound_solmization_3B": rm_define.media_sound_solmization_3B,
            }
        )

        wait_for_complete = False
        if "wait_for_complete" in kw.keys():
            wait_for_complete = kw["wait_for_complete"]
        if "wait_for_complete_flag" in kw.keys():
            wait_for_complete = kw["wait_for_complete_flag"]
        return get_result(self.__play_sound(id, wait_for_complete))

    def take_photos(self):
        return get_result(self.capture())

    def start_recording(self):
        return get_result(self.record(1))

    def stop_recording(self):
        return get_result(self.record(0))


class RobotCtrl(object):
    GIMBAL_CHASSIS_SUB_INFO_PUSH_FREQ = 50

    def __init__(self, event_client, chassis, gimbal):
        self.robot = rm_module.Tank(event_client)
        self.chassis = chassis
        self.gimbal = gimbal
        self.chassis_status_dict = {}
        self.gimbal_status_dict = {}
        self.msg_id = None
        self.init_chassis_pos_flag = 0
        self.mode = None

    ## python and scratch ##
    def set_mode(self, mode):
        result = False
        if type(mode) == str:
            if mode == "gimbal_lead":
                mode = rm_define.robot_mode_chassis_follow
            elif mode == "chassis_lead":
                mode = rm_define.robot_mode_gimbal_follow
            elif mode == "free":
                mode = rm_define.robot_mode_free
            else:
                return False
        CHECK_VALUE_RANGE_AND_TYPE(
            mode, rm_define.robot_mode_free, rm_define.robot_mode_gimbal_follow, int
        )
        logger.info("TANK_CTRL: set work mode, the mode is %s" % mode)
        if (
            mode == rm_define.robot_mode_gimbal_follow
            or mode == rm_define.robot_mode_chassis_follow
            or mode == rm_define.robot_mode_free
        ):
            result = get_result(self.robot.set_work_mode(mode))
            self.set_mode_attr(mode)
        time.sleep(0.05)
        return result

    def get_mode(self):
        if self.mode == rm_define.robot_mode_gimbal_follow:
            return "chassis_lead"
        elif self.mode == rm_define.robot_mode_chassis_follow:
            return "gimbal_lead"
        elif self.mode == rm_define.robot_mode_free:
            return "free"

    def enable_sdk_mode(self):
        return self.robot.set_sdk_mode(1)

    def disable_sdk_mode(self):
        return self.robot.set_sdk_mode(0)

    def set_mode_attr(self, mode):
        self.mode = mode
        if mode == rm_define.robot_mode_gimbal_follow:
            self.chassis.set_mode_attr(rm_define.chassis_sdk_free_mode)
            self.gimbal.set_mode_attr(rm_define.gimbal_yaw_follow_mode)
        elif mode == rm_define.robot_mode_chassis_follow:
            self.chassis.set_mode_attr(rm_define.chassis_sdk_follow_mode)
            self.gimbal.set_mode_attr(rm_define.gimbal_free_mode)
        elif mode == rm_define.robot_mode_free:
            self.chassis.set_mode_attr(rm_define.chassis_sdk_free_mode)
            self.gimbal.set_mode_attr(rm_define.gimbal_free_mode)

    def init(self):
        uuid_list = [
            0xC14CB7C5,  # esc_info
            0xEEB7CECE,  # ns_pos
            0x49A4009C,  # ns_vel
            0xA7985B8D,  # ns_imu
            0x6B986306,  # attitude_info
            0x4A2C6D55,  # ns_sa_status
            0xF79B3C97,  # gimbal_pos
            0x55E9A0FA,  # stick_flag
        ]
        self.sub_gimbal_and_chassis_info(
            uuid_list, self.gimbal_and_chassis_push_info_process
        )

    def sub_gimbal_and_chassis_info(self, uuid_list, callback):
        logger.info("TANK_CTRL: sub gimbal and chassis info")
        duss_result, resp = self.robot.add_gimbal_and_chassis_sub_msg(
            RobotCtrl.GIMBAL_CHASSIS_SUB_INFO_PUSH_FREQ, uuid_list, callback
        )
        if duss_result == rm_define.DUSS_SUCCESS:
            if resp["data"][0] == 0 or resp["data"][0] == 0x23:
                logger.info("TANK_CTRL: sub gimbal and chassis info")
                self.msg_id = resp["data"][3]
            else:
                logger.error(
                    "TANK_CTRL: sub gimbal and chassis info error. error code is 0x%x"
                    % resp["data"][0]
                )
        else:
            logger.error(
                "TANK_CTRL: sub gimbal and chassis info error. error code is %s"
                % duss_result
            )
        return duss_result, resp

    def unsub_gimbal_and_chassis_info(self):
        if self.msg_id != None:
            logger.info(
                "TANK_CTRL: unsub gimbal and chassis info msg id is %s" % self.msg_id
            )
            duss_result, resp = self.robot.del_gimbal_and_chassis_sub_msg(self.msg_id)
            return duss_result, resp
        else:
            return rm_define.DUSS_SUCCESS, None

    def gimbal_and_chassis_push_info_process(self, event_client, msg):
        # esc_info(36 byte) + ns_pos(12 byte) + ns_vel(24 byte) + ns_imu(24 byte) + attitude_info(12 byte) + ns_sa_status(4 byte) + gimbal_pos(9 byte) + stick_flag(1 byte)
        data = msg["data"]
        mode = data[0]
        msg_id = data[1]

        data = data[2:]
        esc_info = data[0:36]
        ns_pos = data[36:48]
        ns_vel = data[48:72]
        ns_imu = data[72:96]
        attitude_info = data[96:108]
        ns_sa_status = data[108:112]
        gimbal_pos = data[112:121]
        stick_flag = data[121]

        self.chassis_status_dict["cur_speed_wheel1"] = tools.byte_to_int16(
            esc_info[0:2]
        )
        self.chassis_status_dict["cur_speed_wheel2"] = tools.byte_to_int16(
            esc_info[2:4]
        )
        self.chassis_status_dict["cur_speed_wheel3"] = tools.byte_to_int16(
            esc_info[4:6]
        )
        self.chassis_status_dict["cur_speed_wheel4"] = tools.byte_to_int16(
            esc_info[6:8]
        )

        self.chassis_status_dict["cur_speed_gx"] = tools.byte_to_float(ns_vel[0:4])
        self.chassis_status_dict["cur_speed_gy"] = tools.byte_to_float(ns_vel[4:8])

        self.chassis_status_dict["cur_speed_bx"] = tools.byte_to_float(ns_vel[12:16])
        self.chassis_status_dict["cur_speed_by"] = tools.byte_to_float(ns_vel[16:20])

        self.chassis_status_dict["cur_speed_wz"] = tools.byte_to_float(ns_imu[20:24])

        self.chassis_status_dict["cur_position_x"] = tools.byte_to_float(ns_pos[0:4])
        self.chassis_status_dict["cur_position_y"] = tools.byte_to_float(ns_pos[4:8])

        self.chassis_status_dict["cur_attitude_yaw"] = tools.byte_to_float(
            attitude_info[0:4]
        )
        self.chassis_status_dict["cur_attitude_pitch"] = tools.byte_to_float(
            attitude_info[4:8]
        )
        self.chassis_status_dict["cur_attitude_roll"] = tools.byte_to_float(
            attitude_info[8:12]
        )

        """
        status = tools.byte_to_uint32(ns_sa_status)

        self.chassis_status_dict['static_flag'] = (status >> 0) & 0x01
        self.chassis_status_dict['uphill_flag'] = (status >> 1) & 0x01
        self.chassis_status_dict['downhill_flag'] = (status >> 2) & 0x01
        self.chassis_status_dict['on_slope_flag'] = (status >> 3) & 0x01
        self.chassis_status_dict['pick_up_flag'] = (status >> 4) & 0x01
        self.chassis_status_dict['slip_flag'] = (status >> 5) & 0x01
        self.chassis_status_dict['impact_x_flag'] = (status >> 6) & 0x01
        self.chassis_status_dict['impact_y_flag'] = (status >> 7) & 0x01
        self.chassis_status_dict['impact_z_flag'] = (status >> 8) & 0x01
        self.chassis_status_dict['roll_over_flag'] = (status >> 9) & 0x01
        self.chassis_status_dict['slp_output_flag'] = (status >> 10) & 0x01

        """

        pitch = tools.to_int16(((gimbal_pos[3] << 8) | gimbal_pos[2]))
        yaw = tools.to_int16(((gimbal_pos[5] << 8) | gimbal_pos[4]))
        self.gimbal_status_dict["pitch_cur"] = pitch / 10
        self.gimbal_status_dict["yaw_cur"] = yaw / 10

        self.gimbal_status_dict["stick_flag"] = stick_flag >> 1 & 0x01
        self.chassis_status_dict["stick_flag"] = (
            stick_flag & 0x01 | self.gimbal_status_dict["stick_flag"]
        )

        if self.init_chassis_pos_flag == 0:
            self.init_chassis_pos_flag = 1
            self.chassis_status_dict["init_position_x"] = self.chassis_status_dict[
                "cur_position_x"
            ]
            self.chassis_status_dict["init_position_y"] = self.chassis_status_dict[
                "cur_position_y"
            ]
            self.chassis_status_dict["init_attitude_yaw"] = self.chassis_status_dict[
                "cur_attitude_yaw"
            ]

        # updata gimbal and chassis status
        self.chassis.update_attitude_status(self.chassis_status_dict)
        self.gimbal.update_attitude_status(self.gimbal_status_dict)

    def exit(self):
        self.unsub_gimbal_and_chassis_info()


class DebugCtrl(object):
    def __init__(self, event_client):
        self.debug = rm_module.Debug(event_client)
        self.vision = VisionCtrl(event_client)

    def test1(self, arg1):
        duss_result = self.debug.test1(arg1)
        self.vision.line_follow_color_set(arg1)
        return duss_result

    def test2(self, arg1, arg2):
        duss_result = self.debug.test2(arg1, arg2)
        return duss_result

    def test3(self, arg1, arg2, arg3):
        duss_result = self.debug.test3(arg1, arg2, arg3)
        return duss_result

    def test4(self, arg1, arg2, arg3, arg4):
        duss_result = self.debug.test4(arg1, arg2, arg3, arg4)
        return duss_result


class MobileCtrl(object):
    def __init__(self, event_client):
        self.mobile = rm_module.Mobile(event_client)
        self.mobile_type_info_parse = {
            rm_define.mobile_info_accel_type: self.accel_data_parse,
            rm_define.mobile_info_atti_type: self.atti_data_parse,
            rm_define.mobile_info_gyro_type: self.gyro_data_parse,
            # mobile_info_gps_type: self.gps_data_parse,
        }
        self.mobile_accel_data = [0, 0, 0]
        self.mobile_gyro_data = [0, 0, 0]
        self.mobile_atti_data = [0, 0, 0]

    def info_push_process(self, event_client, msg):
        data = msg["data"]
        type_info = 0
        type_pos = 0
        length = 0
        while type_pos < len(data):
            mobile_info_type = data[type_pos]
            length = data[type_pos + 1]
            type_pos += 2
            if mobile_info_type in self.mobile_type_info_parse.keys():
                callback_data = data[type_pos : type_pos + length]
                self.mobile_type_info_parse[mobile_info_type](callback_data)
            else:
                logger.warn(
                    "MOBILE_CTRL:no parse key in mobile info push process, the key is %s"
                    % mobile_info_type
                )
                break
            type_pos += length

    def accel_data_parse(self, data):
        accel_x = tools.byte_to_float(data[0:4])
        accel_y = tools.byte_to_float(data[4:8])
        accel_z = tools.byte_to_float(data[8:12])
        self.mobile_accel_data = [accel_x, accel_y, accel_z]

    def gyro_data_parse(self, data):
        gyro_x = tools.byte_to_float(data[0:4])
        gyro_y = tools.byte_to_float(data[4:8])
        gyro_z = tools.byte_to_float(data[8:12])
        self.mobile_gyro_data = [gyro_x, gyro_y, gyro_z]

    def atti_data_parse(self, data):
        atti_pitch = tools.byte_to_float(data[0:4])
        atti_roll = tools.byte_to_float(data[4:8])
        atti_yaw = tools.byte_to_float(data[8:12])
        if atti_pitch >= 180:
            atti_pitch = atti_pitch - 360
        if atti_roll >= 180:
            atti_roll = atti_roll - 360
        if atti_yaw >= 180:
            atti_yaw = atti_yaw - 360
        self.mobile_atti_data = [atti_pitch, atti_roll, atti_yaw]

    def gps_data_parse(self, data):
        pass

    def enable_info_push(self):
        info_id = (
            rm_define.mobile_info_gps_id
            | rm_define.mobile_info_accel_id
            | rm_define.mobile_info_gyro_id
            | rm_define.mobile_info_atti_id
        )
        logger.info("MOBILE_CTR: enable info push")
        return self.mobile.sub_info(info_id, self.info_push_process)

    def disable_info_push(self):
        logger.info("MOBILE_CTR: disable all info push")
        return self.mobile.unsub_all_info()

    def get_accel_ori_data(self):
        return self.mobile_accel_data

    def get_gyro_ori_data(self):
        return self.mobile_gyro_data

    def get_atti_ori_data(self):
        return self.mobile_atti_data

    def get_attitude(self, axis):
        if axis == rm_define.mobile_atti_pitch:
            return self.get_atti_ori_data()[0]
        elif axis == rm_define.mobile_atti_roll:
            return self.get_atti_ori_data()[1]
        elif axis == rm_define.mobile_atti_yaw:
            return self.get_atti_ori_data()[2]

    def get_accel(self, axis):
        if axis == rm_define.mobile_accel_x:
            return self.get_accel_ori_data()[0]
        elif axis == rm_define.mobile_accel_y:
            return self.get_accel_ori_data()[1]
        elif axis == rm_define.mobile_accel_z:
            return self.get_accel_ori_data()[2]

    def get_gyro(self, axis):
        if axis == rm_define.mobile_gyro_x:
            return self.get_gyro_ori_data()[0]
        elif axis == rm_define.mobile_gyro_y:
            return self.get_gyro_ori_data()[1]
        elif axis == rm_define.mobile_gyro_z:
            return self.get_gyro_ori_data()[2]

    def init(self):
        self.enable_info_push()

    def exit(self):
        self.disable_info_push()


class LogCtrl(object):
    def __init__(self, event_client):
        self.mobile = rm_module.Mobile(event_client)
        self.msg_time_enable = True

    # TODO: need to report msg error(ex. too long) to mobile
    def info_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_debug, rm_define.custom_msg_level_info, msg_args
        )

    def debug_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_debug, rm_define.custom_msg_level_debug, msg_args
        )

    def error_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_debug, rm_define.custom_msg_level_error, msg_args
        )

    def fatal_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_debug, rm_define.custom_msg_level_fatal, msg_args
        )

    def print_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_debug, rm_define.custom_msg_level_none, msg_args
        )

    def show_msg(self, *msg_args):
        return self.send_string(
            rm_define.custom_msg_type_show, rm_define.custom_msg_level_none, msg_args
        )

    def send_string(self, msg_type, msg_level, msg_args):
        msg_string = ""
        for var in msg_args:
            msg_string += str(var) + " "
        msg_string = msg_string[0:-1]
        if len(msg_string) > rm_define.custom_msg_max_len:
            msg_string = tools.string_limit(msg_string, rm_define.custom_msg_max_len)
            pass
        msg_string = self._add_string_head(msg_level, msg_string)
        return self.mobile.custom_msg_send(msg_type, msg_level, msg_string)

    def enable_msg_time(self):
        self.msg_time_enable = True

    def disable_msg_time(self):
        self.msg_time_enable = False

    def _add_string_head(self, level, string):
        string_head = {
            rm_define.custom_msg_level_none: "",
            rm_define.custom_msg_level_info: "[INFO ]:",
            rm_define.custom_msg_level_debug: "[DEBUG]:",
            rm_define.custom_msg_level_error: "[ERROR]:",
            rm_define.custom_msg_level_fatal: "[FATAL]:",
        }
        time_str = ""

        if self.msg_time_enable:
            time_str = "[" + time.asctime() + "]" + ":"

        add_head = string_head[level] + time_str + " "

        if add_head == " ":
            add_head == ""

        return add_head + string


class PIDCtrl(object):
    def __init__(self, kp=0, ki=0, kd=0):
        self.kp = 0
        self.ki = 0
        self.kd = 0
        self.kp_item = 0
        self.ki_item = 0
        self.kd_item = 0
        self.error = 0
        self.last_error = 0
        self.last_time = 0

    def set_ctrl_params(self, kp, ki, kd):
        self.kp = kp
        self.ki = ki
        self.kd = kd

    def set_error(self, error):
        self.error = error

    def get_output(self):
        cur_time = time.time()
        if self.last_time == 0:
            self.last_time = cur_time
        else:
            dt = cur_time - self.last_time
            if dt > 0.2:
                self.ki_item = 0
            self.last_time = cur_time
            self.kp_item = self.kp * self.error
            self.ki_item = self.ki_item + 1 * self.ki * self.error
            self.kd_item = self.kd * (self.error - self.last_error) / 1
            self.last_error = self.error
        return self.kp_item + self.ki_item + self.kd_item


class RobotTools(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.sys_time = rm_module.SysTime(event_client)
        self.timer_state = rm_define.timer_stop
        self.timer_start_stamp = 0
        self.timer_total = 0
        self.program_timer_start_t = 0
        self.time_offset = 0

    def get_timer_count(self):
        if self.timer_state == rm_define.timer_start:
            return (
                math.ceil(
                    (time.time() - self.timer_start_stamp + self.timer_total) * 1000
                )
                / 1000
            )
        else:
            return math.ceil(self.timer_total * 1000) / 1000

    def run_time_of_program(self):
        return round(time.time() - self.program_timer_start_t, 2)

    def timer_ctrl(self, ctrl):
        CHECK_VALUE_IN_ENUM_LIST(
            ctrl,
            **{
                "rm_define.timer_start": rm_define.timer_start,
                "rm_define.timer_stop": rm_define.timer_stop,
                "rm_define.timer_reset": rm_define.timer_reset,
            }
        )
        if ctrl == rm_define.timer_start:
            if self.timer_state != rm_define.timer_start:
                self.timer_start_stamp = time.time()
                self.timer_state = rm_define.timer_start
        elif ctrl == rm_define.timer_stop:
            if self.timer_state == rm_define.timer_start:
                self.timer_total = self.timer_total + (
                    time.time() - self.timer_start_stamp
                )
                self.timer_start_stamp = 0
                self.timer_state = rm_define.timer_stop
        elif ctrl == rm_define.timer_reset:
            if self.timer_state != rm_define.timer_reset:
                self.timer_start_stamp = 0
                self.timer_total = 0
                self.timer_state = rm_define.timer_reset
        else:
            pass

    def timer_current(self):
        return self.get_timer_count()

    def get_localtime(self, time_unit):
        CHECK_VALUE_IN_ENUM_LIST(
            time_unit,
            **{
                "rm_define.localtime_year": rm_define.localtime_year,
                "rm_define.localtime_month": rm_define.localtime_month,
                "rm_define.localtime_day": rm_define.localtime_day,
                "rm_define.localtime_hour": rm_define.localtime_hour,
                "rm_define.localtime_minute": rm_define.localtime_minute,
                "rm_define.localtime_second": rm_define.localtime_second,
            }
        )
        if time_unit == rm_define.localtime_year:
            return time.localtime().tm_year
        elif time_unit == rm_define.localtime_month:
            return time.localtime().tm_mon
        elif time_unit == rm_define.localtime_day:
            return time.localtime().tm_mday
        elif time_unit == rm_define.localtime_hour:
            return time.localtime().tm_hour
        elif time_unit == rm_define.localtime_minute:
            return time.localtime().tm_min
        elif time_unit == rm_define.localtime_second:
            return time.localtime().tm_sec

    def get_unixtime(self):
        if self.time_offset == 0:
            duss_result, resp = self.sys_time.get_latest_sys_time()
            data = resp["data"]
            unixtime = (
                tools.byte_to_uint32(data[1:5]) << 32 | tools.byte_to_uint32(data[5:9])
            ) / 1000.0
            logger.info("SYS_TIME: get_unixtime is:%s" % (unixtime))
            self.time_offset = time.time() - unixtime
            return unixtime
        else:
            return time.time() - self.time_offset

    def init(self):
        pass

    def program_timer_start(self):
        self.program_timer_start_t = time.time()


class ModulesStatusCtrl(object):
    ALL_MODULE_STATUS_MSG_ID = (
        rm_define.system_id,
        rm_define.camera_id,
        rm_define.chassis_id,
        rm_define.gimbal_id,
        rm_define.gun_id,
        rm_define.armor1_id,
        rm_define.armor2_id,
        rm_define.armor3_id,
        rm_define.armor4_id,
        rm_define.armor5_id,
        rm_define.armor6_id,
        rm_define.gun_id,
        rm_define.sensor_adapter1_id,
        rm_define.sensor_adapter2_id,
        rm_define.sensor_adapter3_id,
        rm_define.sensor_adapter4_id,
        rm_define.sensor_adapter5_id,
        rm_define.sensor_adapter6_id,
        rm_define.tof1_id,
        rm_define.tof2_id,
        rm_define.tof3_id,
        rm_define.tof4_id,
        rm_define.servo1_id,
        rm_define.servo2_id,
        rm_define.servo3_id,
        rm_define.servo4_id,
        rm_define.robotic_gripper_id,
        rm_define.robotic_arm_id,
    )
    EXTEND_MODULE_ID = (
        rm_define.sensor_adapter1_id,
        rm_define.sensor_adapter2_id,
        rm_define.sensor_adapter3_id,
        rm_define.sensor_adapter4_id,
        rm_define.sensor_adapter5_id,
        rm_define.sensor_adapter6_id,
        rm_define.tof1_id,
        rm_define.tof2_id,
        rm_define.tof3_id,
        rm_define.tof4_id,
        rm_define.servo1_id,
        rm_define.servo2_id,
        rm_define.servo3_id,
        rm_define.servo4_id,
        rm_define.robotic_gripper_id,
        rm_define.robotic_arm_id,
    )
    MODULE_STATUS_MSG_ID_TO_MODULE_ID_MAP = {
        0x0100: rm_define.system_id,
        0x0104: rm_define.camera_id,
        0x0300: rm_define.chassis_id,
        0x0400: rm_define.gimbal_id,
        0x1700: rm_define.gun_id,
        0x1801: rm_define.armor1_id,
        0x1802: rm_define.armor2_id,
        0x1803: rm_define.armor3_id,
        0x1804: rm_define.armor4_id,
        0x1805: rm_define.armor5_id,
        0x1806: rm_define.armor6_id,
        0x1601: rm_define.sensor_adapter1_id,
        0x1602: rm_define.sensor_adapter2_id,
        0x1603: rm_define.sensor_adapter3_id,
        0x1604: rm_define.sensor_adapter4_id,
        0x1605: rm_define.sensor_adapter5_id,
        0x1606: rm_define.sensor_adapter6_id,
        0x1201: rm_define.tof1_id,
        0x1202: rm_define.tof2_id,
        0x1203: rm_define.tof3_id,
        0x1204: rm_define.tof4_id,
        0x0318: rm_define.servo1_id,
        0x0319: rm_define.servo2_id,
        0x031A: rm_define.servo3_id,
        0x031B: rm_define.servo4_id,
        0x031E: rm_define.robotic_arm_id,
        0x031F: rm_define.robotic_gripper_id,
    }

    def __init__(self, event_client):
        self.modulesStatus = rm_module.ModulesStatus(event_client)
        self.module_status = {}
        self.is_edu_version = False

    def init(self):
        for status_msg_id in self.ALL_MODULE_STATUS_MSG_ID:
            self.module_status[status_msg_id] = rm_define.module_status_offline

        self.modulesStatus.sub_module_status_info(self.module_status_process)

    def set_edu_status(self, status):
        self.is_edu_version = status

    def check_module_enable(self, event_client):
        event_client.event_msg_invalid_check_callback_register(
            self.check_module_invalid
        )
        logger.info("Enable the module status check!\n")

    def _check_module_status(self, module_id, status):
        if module_id in self.module_status.keys():
            return self.module_status[module_id] == status
        else:
            return True

    def _check_is_edu_module(self, module_id):
        if module_id in self.EXTEND_MODULE_ID:
            return True
        else:
            return False

    def check_module_invalid(self, event_msg):
        if self._check_is_edu_module(event_msg.module_id):
            if not self.is_edu_version:
                logger.info("Robot is S1, but want to use S1_EDU`s module")
                return True, rm_define.FAT_DEVICE_NOT_SUPPORT
        if self._check_module_status(
            (event_msg.module_id), rm_define.module_status_offline
        ):
            return True, rm_define.BLOCK_ERR_MODULE_OFFLINE
        elif self._check_module_status(
            (event_msg.module_id), rm_define.module_status_error
        ):
            return True, rm_define.BLOCK_ERR_MODULE_INVALID
        else:
            return False, None

    def module_status_process(self, event_client, msg):
        data = msg["data"]
        online_module_num = data[0]
        online_module_list = []
        invalid_module_list = []
        data = data[1:]
        for i in range(online_module_num):
            module_status_msg_id = tools.byte_to_uint16(data[0:2])
            if (
                module_status_msg_id
                in self.MODULE_STATUS_MSG_ID_TO_MODULE_ID_MAP.keys()
            ):
                online_module_list.append(
                    self.MODULE_STATUS_MSG_ID_TO_MODULE_ID_MAP[module_status_msg_id]
                )
            msg_num = data[2]
            msg_info = data[3 : msg_num * 2 + 3]
            data = data[3 + msg_num * 2 :]
            for n in range(msg_num):
                msg_code = tools.byte_to_uint16(msg_info[n * 2 : n * 2 + 2])
                if msg_code >> 14 == rm_define.module_msg_type_error:
                    invalid_module_list.append(module_status_msg_id)
                    break

        offline_module_list = list(
            set(self.ALL_MODULE_STATUS_MSG_ID) - set(online_module_list)
        )

        for module_id in online_module_list:
            if module_id in self.module_status.keys():
                self.module_status[module_id] = rm_define.module_status_online
        for module_id in offline_module_list:
            if module_id in self.module_status.keys():
                self.module_status[module_id] = rm_define.module_status_offline
        for module_id in invalid_module_list:
            if module_id in self.module_status.keys():
                self.module_status[module_id] = rm_define.module_status_error


class SDKCtrl(object):
    def __init__(self, event_client):
        self.sdk_ctrl = rm_module.SDKModule(event_client)

    def sdk_on(self, mode="WIFI"):
        if type(mode) == str:
            if mode.upper() == "WIFI":
                return self.sdk_ctrl.sdk_on(0)
            elif mode.upper() == "RNDIS":
                return self.sdk_ctrl.sdk_on(1)
            else:
                return None
        else:
            return None

    def sdk_off(self):
        return self.sdk_ctrl.sdk_off()

    def stream_on(self):
        return self.sdk_ctrl.stream_on()

    def stream_off(self):
        return self.sdk_ctrl.stream_off()

    def audio_on(self):
        return self.sdk_ctrl.audio_on()

    def audio_off(self):
        return self.sdk_ctrl.audio_off()

    def stop(self):
        self.stream_off()
        self.audio_off()
        return self.sdk_off()

    def exit(self):
        return self.stop()


class SensorAdapterCtrl(RobotCtrlTool):
    func_str_dict = {
        "rm_define.cond_sensor_adapter1_port1_high_event": rm_define.cond_sensor_adapter1_port1_high_event,
        "rm_define.cond_sensor_adapter1_port1_low_event": rm_define.cond_sensor_adapter1_port1_low_event,
        "rm_define.cond_sensor_adapter1_port1_trigger_event": rm_define.cond_sensor_adapter1_port1_trigger_event,
        "rm_define.cond_sensor_adapter1_port2_high_event": rm_define.cond_sensor_adapter1_port2_high_event,
        "rm_define.cond_sensor_adapter1_port2_low_event": rm_define.cond_sensor_adapter1_port2_low_event,
        "rm_define.cond_sensor_adapter1_port2_trigger_event": rm_define.cond_sensor_adapter1_port2_trigger_event,
        "rm_define.cond_sensor_adapter2_port1_high_event": rm_define.cond_sensor_adapter2_port1_high_event,
        "rm_define.cond_sensor_adapter2_port1_low_event": rm_define.cond_sensor_adapter2_port1_low_event,
        "rm_define.cond_sensor_adapter2_port1_trigger_event": rm_define.cond_sensor_adapter2_port1_trigger_event,
        "rm_define.cond_sensor_adapter2_port2_high_event": rm_define.cond_sensor_adapter2_port2_high_event,
        "rm_define.cond_sensor_adapter2_port2_low_event": rm_define.cond_sensor_adapter2_port2_low_event,
        "rm_define.cond_sensor_adapter2_port2_trigger_event": rm_define.cond_sensor_adapter2_port2_trigger_event,
        "rm_define.cond_sensor_adapter3_port1_high_event": rm_define.cond_sensor_adapter3_port1_high_event,
        "rm_define.cond_sensor_adapter3_port1_low_event": rm_define.cond_sensor_adapter3_port1_low_event,
        "rm_define.cond_sensor_adapter3_port1_trigger_event": rm_define.cond_sensor_adapter3_port1_trigger_event,
        "rm_define.cond_sensor_adapter3_port2_high_event": rm_define.cond_sensor_adapter3_port2_high_event,
        "rm_define.cond_sensor_adapter3_port2_low_event": rm_define.cond_sensor_adapter3_port2_low_event,
        "rm_define.cond_sensor_adapter3_port2_trigger_event": rm_define.cond_sensor_adapter3_port2_trigger_event,
        "rm_define.cond_sensor_adapter4_port1_high_event": rm_define.cond_sensor_adapter4_port1_high_event,
        "rm_define.cond_sensor_adapter4_port1_low_event": rm_define.cond_sensor_adapter4_port1_low_event,
        "rm_define.cond_sensor_adapter4_port1_trigger_event": rm_define.cond_sensor_adapter4_port1_trigger_event,
        "rm_define.cond_sensor_adapter4_port2_high_event": rm_define.cond_sensor_adapter4_port2_high_event,
        "rm_define.cond_sensor_adapter4_port2_low_event": rm_define.cond_sensor_adapter4_port2_low_event,
        "rm_define.cond_sensor_adapter4_port2_trigger_event": rm_define.cond_sensor_adapter4_port2_trigger_event,
        "rm_define.cond_sensor_adapter5_port1_high_event": rm_define.cond_sensor_adapter5_port1_high_event,
        "rm_define.cond_sensor_adapter5_port1_low_event": rm_define.cond_sensor_adapter5_port1_low_event,
        "rm_define.cond_sensor_adapter5_port1_trigger_event": rm_define.cond_sensor_adapter5_port1_trigger_event,
        "rm_define.cond_sensor_adapter5_port2_high_event": rm_define.cond_sensor_adapter5_port2_high_event,
        "rm_define.cond_sensor_adapter5_port2_low_event": rm_define.cond_sensor_adapter5_port2_low_event,
        "rm_define.cond_sensor_adapter5_port2_trigger_event": rm_define.cond_sensor_adapter5_port2_trigger_event,
        "rm_define.cond_sensor_adapter6_port1_high_event": rm_define.cond_sensor_adapter6_port1_high_event,
        "rm_define.cond_sensor_adapter6_port1_low_event": rm_define.cond_sensor_adapter6_port1_low_event,
        "rm_define.cond_sensor_adapter6_port1_trigger_event": rm_define.cond_sensor_adapter6_port1_trigger_event,
        "rm_define.cond_sensor_adapter6_port2_high_event": rm_define.cond_sensor_adapter6_port2_high_event,
        "rm_define.cond_sensor_adapter6_port2_low_event": rm_define.cond_sensor_adapter6_port2_low_event,
        "rm_define.cond_sensor_adapter6_port2_trigger_event": rm_define.cond_sensor_adapter6_port2_trigger_event,
        "rm_define.cond_sensor_adapter7_port1_high_event": rm_define.cond_sensor_adapter7_port1_high_event,
        "rm_define.cond_sensor_adapter7_port1_low_event": rm_define.cond_sensor_adapter7_port1_low_event,
        "rm_define.cond_sensor_adapter7_port1_trigger_event": rm_define.cond_sensor_adapter7_port1_trigger_event,
        "rm_define.cond_sensor_adapter7_port2_high_event": rm_define.cond_sensor_adapter7_port2_high_event,
        "rm_define.cond_sensor_adapter7_port2_low_event": rm_define.cond_sensor_adapter7_port2_low_event,
        "rm_define.cond_sensor_adapter7_port2_trigger_event": rm_define.cond_sensor_adapter7_port2_trigger_event,
    }

    def __init__(self, event_client):
        super().__init__(event_client)
        self.sensor_adapter = rm_module.SensorAdapter(event_client)
        self.event_client = event_client

        self.condition_event_table = {}
        self.condition_event_time = {}
        self.condition_wait_event_list = {}
        self.condition_mutex = threading.Lock()
        self.event_init()

        self.sdk_event_push_callback = None
        self.sdk_io_level_event_push_enable_flag = False

    def event_init(self):
        self.pulse_event_cb = []
        for id in range(1, 8):
            for port in range(1, 3):
                self.pulse_event_cb.append(
                    "sensor_adapter" + str(id) + "_port" + str(port) + "_high"
                )
                self.pulse_event_cb.append(
                    "sensor_adapter" + str(id) + "_port" + str(port) + "_low"
                )
                self.pulse_event_cb.append(
                    "sensor_adapter" + str(id) + "_port" + str(port) + "_trigger"
                )

        for event_name in self.pulse_event_cb:
            self.event_client.event_callback_register(event_name, dummy_callback)
            logger.info("event_init: %s" % event_name)

        self.sensor_adapter.pulse_event_register(self.pulse_event_process)
        resp = self.sensor_adapter.set_sensor_adapter_param(
            0, 1, **{"set_mask": 2, "adc_accuracy": 0, "push_freq": 0, "io_event": 1}
        )
        self.sensor_adapter.set_sensor_adapter_param(
            0, 2, **{"set_mask": 2, "adc_accuracy": 0, "push_freq": 0, "io_event": 1}
        )

    # called by event_client thread
    def pulse_event_process(self, event_client, msg):
        logger.info("SENSORADAPTER_CTRL: PULSE EVENT PROCESS.")
        data = msg["data"]
        board_id = data[0]
        port_num = data[1]
        io_value = data[2]
        logger.info(
            "board_id %d, port_num %d, io_value %d" % (board_id, port_num, io_value)
        )

        event = {}
        if self.sdk_event_push_callback:
            if self.sdk_io_level_event_push_enable_flag:
                event["io_level"] = (port_num, io_value)
            self.sdk_event_push_callback(event)

        callback_data = io_value

        for board in range(1, 8):
            for port in range(1, 3):
                func_str = "sensor_adapter" + str(board) + "_port" + str(port) + "_high"
                if (
                    board_id == board
                    and port_num == port
                    and io_value == 1
                    and func_str in self.pulse_event_cb
                ):
                    self.condition_mutex.acquire()
                    self._wakeup_condition_waiting(func_str)
                    self.condition_event_time[func_str] = time.time()
                    self.condition_event_table[func_str] = True
                    self.condition_mutex.release()
                    self.event_client.event_come_to_process(func_str)
                    logger.info("SENSORADAPTER_CTRL: %s process event." % func_str)

                func_str = "sensor_adapter" + str(board) + "_port" + str(port) + "_low"
                if (
                    board_id == board
                    and port_num == port
                    and io_value == 0
                    and func_str in self.pulse_event_cb
                ):
                    self.condition_mutex.acquire()
                    self._wakeup_condition_waiting(func_str)
                    self.condition_event_time[func_str] = time.time()
                    self.condition_event_table[func_str] = True
                    self.condition_mutex.release()
                    self.event_client.event_come_to_process(func_str)
                    logger.info("SENSORADAPTER_CTRL: %s process event." % func_str)

                func_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_trigger"
                )
                if (
                    board_id == board
                    and port_num == port
                    and func_str in self.pulse_event_cb
                ):
                    self.condition_mutex.acquire()
                    self._wakeup_condition_waiting(func_str)
                    self.condition_event_time[func_str] = time.time()
                    self.condition_event_table[func_str] = True
                    self.condition_mutex.release()
                    self.event_client.event_come_to_process(func_str)
                    logger.info("SENSORADAPTER_CTRL: %s process event." % func_str)

    def _wakeup_condition_waiting(self, func_str):
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list[func_str].set()

    def register_event(self, func_dict):
        for board in range(1, 8):
            for port in range(1, 3):
                event_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_high_event"
                )
                sensor_adapter_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_high"
                )
                if event_str in func_dict.keys():
                    self.event_client.event_callback_register(
                        sensor_adapter_str, func_dict[event_str]
                    )
                    logger.info(
                        "SENSORADAPTER_CTRL: register event  %s" % sensor_adapter_str
                    )
                event_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_low_event"
                )
                sensor_adapter_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_low"
                )
                if event_str in func_dict.keys():
                    self.event_client.event_callback_register(
                        sensor_adapter_str, func_dict[event_str]
                    )
                    logger.info(
                        "SENSORADAPTER_CTRL: register event  %s" % sensor_adapter_str
                    )
                event_str = (
                    "sensor_adapter"
                    + str(board)
                    + "_port"
                    + str(port)
                    + "_trigger_event"
                )
                sensor_adapter_str = (
                    "sensor_adapter" + str(board) + "_port" + str(port) + "_trigger"
                )
                if event_str in func_dict.keys():
                    self.event_client.event_callback_register(
                        sensor_adapter_str, func_dict[event_str]
                    )
                    logger.info(
                        "SENSORADAPTER_CTRL: register event  %s" % sensor_adapter_str
                    )

    def get_sensor_adapter_adc(self, sensor_adapter_id, port_num):
        duss_result, resp = self.sensor_adapter.get_sensor_adapter_data(
            sensor_adapter_id, port_num
        )
        if duss_result == rm_define.DUSS_SUCCESS:
            adc_value = tools.byte_to_uint16(resp["data"][2:4])
            return adc_value
        return 0

    def get_sensor_adapter_pulse_period(self, sensor_adapter_id, port_num):
        duss_result, resp = self.sensor_adapter.get_sensor_adapter_data(
            sensor_adapter_id, port_num
        )
        if duss_result == rm_define.DUSS_SUCCESS:
            pulse_peroid = tools.byte_to_uint32(resp["data"][5:9])
            return pulse_peroid
        else:
            return 0

    def get_sensor_adapter_io_level(self, sensor_adapter_id, port_num):
        duss_result, resp = self.sensor_adapter.get_sensor_adapter_data(
            sensor_adapter_id, port_num
        )
        if duss_result == rm_define.DUSS_SUCCESS:
            io_level = resp["data"][4]
            return io_level
        return 0

    def sdk_event_push_enable_flag_set(self, io_level_flag=None, reserve=None):
        if io_level_flag == "on":
            self.sdk_io_level_event_push_enable_flag = True
        elif io_level_flag == "off":
            self.sdk_io_level_event_push_enable_flag = False
        else:
            return False

    def sdk_event_push_callback_register(self, callback):
        if callable(callback):
            self.sdk_event_push_callback = callback

    def cond_wait(self, func_str):
        logger.info("SENSORADAPTER_CTRL: cond wait, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(func_str, **self.func_str_dict)
        condition_wait_event = threading.Event()
        self._cond_wait_register(func_str, condition_wait_event)
        self.robot_sleep(3600 * 1000, self.check_cond_wait_event, func_str)
        self._cond_wait_unregister(func_str)

    def check_condition(self, func_str):
        logger.info("SENSORADAPTER_CTRL: check condition, func_str is %s" % (func_str))
        CHECK_VALUE_IN_ENUM_LIST(func_str, **self.func_str_dict)
        self.condition_mutex.acquire()
        event_happen = False
        if func_str in self.condition_event_table.keys():
            curr_time = time.time()
            if curr_time - self.condition_event_time[func_str] > 1.0:
                self.condition_event_table[func_str] = False
            event_happen = self.condition_event_table[func_str]
            self.condition_event_table[func_str] = False
        self.condition_mutex.release()
        return event_happen

    def check_cond_wait_event(self, func_str):
        logger.info(
            "SENSORADAPTER_CTRL: check cond wait event, func_str is %s" % (func_str)
        )
        event_state = False
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            event_state = self.condition_wait_event_list[func_str].isSet()
        self.condition_mutex.release()
        return event_state

    def _cond_wait_register(self, func_str, wait_event):
        self.condition_mutex.acquire()
        wait_event.clear()
        self.condition_wait_event_list[func_str] = wait_event
        self.condition_mutex.release()

    def _cond_wait_unregister(self, func_str):
        self.condition_mutex.acquire()
        if func_str in self.condition_wait_event_list.keys():
            self.condition_wait_event_list.pop(func_str)
        self.condition_mutex.release()

    def init(self):
        pass

    def stop(self):
        logger.error("SENSORADAPTER_CTRL: stop")

    def exit(self):
        logger.error("SENSORADAPTER_CTRL: exit")
        self.sensor_adapter.pulse_event_unregister()


class IrDistanceSensorCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.event_client = event_client
        self.ir_distance_sensor = rm_module.IrDistanceSensor(event_client)

        self.condition_wait_event_dict = {}
        self.condition_mutex = threading.Lock()

        self.check_condition_mutex = threading.Lock()
        self.check_condition_table = {}

        self.distance_info = {}
        self.event_callback_attr = {}

    @event_register
    def enable_measure(self, port_id):
        duss_result = rm_define.DUSS_SUCCESS
        if port_id == 0:
            for port_id in range(1, 5):
                self.ir_distance_sensor.measure_ctrl(port_id, 1)
                self.ir_distance_sensor.sub_tof_data_info_push(
                    port_id, self.tof_push_info_process
                )
        else:
            duss_result = self.ir_distance_sensor.measure_ctrl(port_id, 1)
            if duss_result == rm_define.DUSS_SUCCESS:
                self.ir_distance_sensor.sub_tof_data_info_push(
                    port_id, self.tof_push_info_process
                )

        return get_task_dict(None, "action_immediate", (port_id), result=duss_result)

    @event_register
    def disable_measure(self, port_id):
        duss_result = rm_define.DUSS_SUCCESS
        if port_id == 0:
            for port_id in range(1, 5):
                self.ir_distance_sensor.measure_ctrl(port_id, 0)
                self.ir_distance_sensor.unsub_tof_data_info_push(port_id)
            self.distance_info = {}

        else:
            duss_result = self.ir_distance_sensor.measure_ctrl(port_id, 0)
            if duss_result == rm_define.DUSS_SUCCESS:
                self.ir_distance_sensor.unsub_tof_data_info_push(port_id)
                if port_id in self.distance_info.keys():
                    self.distance_info[port_id] = 0

        return get_task_dict(None, "action_immediate", (port_id), result=duss_result)

    def measure_ctrl(self, enable):
        if enable == "on":
            return self.enable_measure(0)
        elif enable == "off":
            return self.disable_measure(0)
        else:
            return False

    def get_distance_info(self, port_id):
        if port_id in self.distance_info.keys():
            return self.distance_info[port_id] / 10
        else:
            return 0

    def tof_push_info_process(self, event_client, msg):
        data = msg["data"]
        # port_id = msg['sender'] - rm_define.tof_id
        port_id = data[0]
        flag = data[3]
        if flag == 255:
            # MSB, LSB
            distance = data[4] << 8 | data[5]
        else:
            distance = -flag
        # distance = tools.byte_to_uint16([data[3:5]])
        self.distance_info[port_id] = distance

        self._wakeup_condition_waitting(port_id, distance)
        self._add_check_condition_table(port_id, distance)

        if port_id in self.event_callback_attr.keys():
            for item in self.event_callback_attr[port_id]:
                if self._comp(item["comp"], distance, item["dist"]):
                    func_str = item["func_str"]
                    self.event_client.event_come_to_process(func_str, distance)
                    logger.info("IR_SENSOR_Ctrl: %s process event." % func_str)

    def _wakeup_condition_waitting(self, port_id, distance):
        if port_id in self.condition_wait_event_dict.keys():
            for item in self.condition_wait_event_dict[port_id]:
                if self._comp(item["attr"]["comp"], distance, item["attr"]["dist"]):
                    item["event"].set()
                    # one thread mode, so return
                    return

    def _add_check_condition_table(self, port_id, distance):
        self.check_condition_mutex.acquire()
        self.check_condition_table[port_id] = {
            "distance": distance,
            "time": time.time(),
            "flag": True,
        }
        self.check_condition_mutex.release()

    def register_event(self, func_dict):
        for (key, value) in func_dict.items():
            if re.match(
                r"^ir_distance_[1-9]{1}?_(lt|le|eq|ge|gt){1}?_[0-9]*_{0,1}?[0-9]+_event$",
                key,
            ) and callable(value):
                port_id, comp, dist = self._parse_data(key)
                t_d = {"id": port_id, "comp": comp, "dist": dist, "func_str": key}
                if port_id not in self.event_callback_attr.keys():
                    self.event_callback_attr[port_id] = []
                self.event_callback_attr[port_id].append(t_d)
                self.event_client.event_callback_register(key, value)
                # self.wait_event_callback_need_data_set_add(key)

    def check_condition(self, func_str):
        self.condition_mutex.acquire()
        event_happen = False
        port_id, comp, dist = self._parse_data(func_str)
        if port_id in self.check_condition_table.keys():
            curr_time = time.time()
            if curr_time - self.check_condition_table[port_id]["time"] > 1.0:
                self.check_condition_table[port_id]["flag"] = False
            if self.check_condition_table[port_id]["flag"]:
                event_happen = self._comp(
                    comp, self.check_condition_table[port_id]["distance"], dist
                )
        self.condition_mutex.release()
        return event_happen

    def cond_wait(self, func_str):
        if re.match(
            r"^ir_distance_[1-9]{1}?_(lt|le|eq|ge|gt){1}?_[0-9]*_{0,1}?[0-9]+$",
            func_str,
        ):
            condition_wait_event = threading.Event()
            port_id, comp, dist = self._parse_data(func_str)
            t_d = {"id": port_id, "comp": comp, "dist": dist}
            self._cond_wait_register(t_d, condition_wait_event)
            self.robot_sleep(3600 * 1000, self.check_cond_wait_event, t_d)
            self._cond_wait_unregister(t_d)
        else:
            return False

    def check_cond_wait_event(self, func_dict):
        event_state = False
        self.condition_mutex.acquire()
        if func_dict["id"] in self.condition_wait_event_dict.keys():
            for item in self.condition_wait_event_dict[func_dict["id"]]:
                if (
                    item["attr"]["comp"] == func_dict["comp"]
                    and item["attr"]["dist"] == func_dict["dist"]
                ):
                    event_state = item["event"].isSet()
                    break
        self.condition_mutex.release()
        return event_state

    def _cond_wait_register(self, func_dict, wait_event):
        self.condition_mutex.acquire()
        wait_event.clear()
        if func_dict["id"] not in self.condition_wait_event_dict.keys():
            self.condition_wait_event_dict[func_dict["id"]] = []
        self.condition_wait_event_dict[func_dict["id"]].append(
            {"attr": func_dict, "event": wait_event}
        )
        self.condition_mutex.release()

    def _cond_wait_unregister(self, func_dict):
        self.condition_mutex.acquire()
        if func_dict["id"] in self.condition_wait_event_dict.keys():
            for item in self.condition_wait_event_dict[func_dict["id"]]:
                if (
                    item["attr"]["comp"] == func_dict["comp"]
                    and item["attr"]["dist"] == func_dict["dist"]
                ):
                    self.condition_wait_event_dict[func_dict["id"]].remove(item)
                    break
        self.condition_mutex.release()

    def _parse_data(self, string):
        attr = string.split("_")[2:]
        port_id = int(attr[0])
        comp = attr[1]
        cm = int(attr[2])
        mm = 0
        if len(attr) >= 5:
            mm = int(attr[3])
        dist = cm * 10 + mm
        return port_id, comp, dist

    def _comp(self, comp_str, param1, param2):
        if (
            (comp_str == "lt" and param1 < param2)
            or (comp_str == "le" and param1 <= param2)
            or (comp_str == "eq" and param1 == param2)
            or (comp_str == "ge" and param1 >= param2)
            or (comp_str == "gt" and param1 > param2)
        ):
            return True
        else:
            return False

    def init(self):
        pass
        # self.ir_distance_sensor.sub_tof_data_info_push(self.tof_push_info_process)

    def exit(self):
        self.disable_measure(0)


class RoboticGripperCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.robotic_gripper = rm_module.RoboticGripper(event_client)
        self.event_client = event_client
        self.power_level_mapping = {
            1: 165,
            2: 330,
            3: 495,
            4: 660,
        }
        self.power_level = 1

        self.action_status = "stop"

    @event_register
    def open(self, level=None):
        logger.info("ROBOTICGRIPPER_CTRL: open")
        duss_result = rm_define.DUSS_SUCCESS
        if not level:
            level = self.power_level
        duss_result = self.robotic_gripper.robotic_gripper_ctrl(
            1, rm_define.gripper_open, self.power_level_mapping[level]
        )
        self.action_status = "open"
        return get_task_dict(None, "action_immediate", (level), result=duss_result)

    @event_register
    def close(self, level=None):
        logger.info("ROBOTICGRIPPER_CTRL: close")
        duss_result = rm_define.DUSS_SUCCESS
        if not level:
            level = self.power_level
        self.robotic_gripper.robotic_gripper_ctrl(
            1, rm_define.gripper_close, self.power_level_mapping[level]
        )
        self.action_status = "close"
        return get_task_dict(None, "action_immediate", (level), result=duss_result)

    @event_register
    def stop(self):
        logger.info("ROBOTICGRIPPER_CTRL: stop")
        duss_result = rm_define.DUSS_SUCCESS
        self.robotic_gripper.robotic_gripper_ctrl(
            1, rm_define.gripper_stop, self.power_level_mapping[self.power_level]
        )
        self.action_status = "stop"
        return get_task_dict(None, "action_immediate", (), result=duss_result)

    def update_power_level(self, level):
        logger.info("ROBOTICGRIPPER_CTRL: set level %d" % (level))
        self.power_level = level
        if self.action_status == "open":
            self.open()
        else:
            self.close()

    def get_status(self):
        logger.info("ROBOTICGRIPPER_CTRL: get status")
        duss_result, resp = self.robotic_gripper.get_robotic_gripper_status(1)
        if duss_result == rm_define.DUSS_SUCCESS:
            gripper_status = resp["data"][1]
            logger.info(resp["data"])
            return gripper_status
        else:
            return None

    def is_closed(self):
        return self.get_status() == 2

    def is_open(self):
        return self.get_status() == 1

    def init(self):
        pass

    def exit(self):
        logger.error("ROBOTICGRIPPER_CTRL: exit")
        return self.stop()


class ServoCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.servo = rm_module.Servo(event_client)
        self.event_client = event_client

    def get_angle(self, servo_id):
        duss_result, resp = self.servo.get_servo_angle(servo_id)
        if duss_result == rm_define.DUSS_SUCCESS:
            data = resp["data"]
            servo_angle = tools.byte_to_int32(data[1:5]) / 10.0 - 180
            return servo_angle
        else:
            return 0

    @event_register
    def set_angle(self, servo_id, angle, wait_for_complete=False):
        logger.info("ROBOTICGRIPPER_CTRL: set angle")
        angle = int((angle + 180) * 10)

        if wait_for_complete == None:
            wait_for_complete = False

        if wait_for_complete == True:
            self.interrupt_func_register(self.angle_set_interrupt_callback, servo_id)
            self.finished_func_register(self.angle_set_finished_callback, servo_id)

            duss_result, identify = self.servo.set_servo_angle(
                servo_id, angle, rm_define.TASK
            )
            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify,
                    "task",
                    (servo_id, angle, wait_for_complete),
                    result=duss_result,
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None,
                    "no_task",
                    (servo_id, angle, wait_for_complete),
                    result=duss_result,
                )
        else:
            duss_result, resp = self.servo.set_servo_angle(
                servo_id, angle, rm_define.NO_TASK
            )
            return get_task_dict(
                None,
                "action_immediate",
                (servo_id, angle, wait_for_complete),
                result=duss_result,
            )

    def set_speed(self, servo_id, speed):
        self.servo.set_servo_speed(servo_id, speed)

    def recenter(self, servo_id, wait_for_complete=True):
        if wait_for_complete == None:
            wait_for_complete = False

        self.set_angle(servo_id, 0, wait_for_complete)

    def angle_set_interrupt_callback(self, servo_id):
        # self.stop(servo_id)
        self.servo.set_servo_angle_task_stop()

    def angle_set_finished_callback(self, servo_id):
        pass
        # self.stop(servo_id)

    def init(self):
        pass

    def stop(self, servo_id=0):
        if servo_id == 0 or servo_id == None:
            for id in range(1, 4):
                self.servo.set_servo_speed(id, 0)
        else:
            self.servo.set_servo_speed(servo_id, 0)
        return 0

    def exit(self):
        logger.error("SERVO_CTRL: exit")
        return self.stop(0)


class RoboticArmCtrl(RobotCtrlTool):
    def __init__(self, event_client):
        super().__init__(event_client)
        self.robotic_arm = rm_module.RoboticArm(event_client)
        self.event_client = event_client

    @event_register
    def move(self, x, y, wait_for_complete=False):
        if wait_for_complete == None:
            wait_for_complete = False

        if wait_for_complete == True:
            self.interrupt_func_register(self.arm_move_interrupt_callback)
            self.finished_func_register(self.arm_move_set_finished_callback)

            duss_result, identify = self.robotic_arm.robotic_arm_move_ctrl(
                1, rm_define.opposite_move, rm_define.mask_xy, x, y, 0, rm_define.TASK
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (x, y, wait_for_complete), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (x, y, wait_for_complete), result=duss_result
                )
        else:
            duss_result = self.robotic_arm.robotic_arm_move_ctrl(
                1,
                rm_define.opposite_move,
                rm_define.mask_xy,
                x,
                y,
                0,
                rm_define.NO_TASK,
            )
            return get_task_dict(
                None, "action_immediate", (x, y, wait_for_complete), result=duss_result
            )

    @event_register
    def moveto(self, x, y, wait_for_complete=False):
        if wait_for_complete == None:
            wait_for_complete = False

        if wait_for_complete == True:
            self.interrupt_func_register(self.arm_move_interrupt_callback)
            self.finished_func_register(self.arm_move_set_finished_callback)

            duss_result, identify = self.robotic_arm.robotic_arm_move_ctrl(
                1, rm_define.absolute_move, rm_define.mask_xy, x, y, 0, rm_define.TASK
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (x, y, wait_for_complete), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (x, y, wait_for_complete), result=duss_result
                )
        else:
            duss_result, resp = self.robotic_arm.robotic_arm_move_ctrl(
                1,
                rm_define.absolute_move,
                rm_define.mask_xy,
                x,
                y,
                0,
                rm_define.NO_TASK,
            )
            return get_task_dict(
                None, "action_immediate", (x, y, wait_for_complete), result=duss_result
            )

    @event_register
    def recenter(self, wait_for_complete=False):
        if wait_for_complete == None:
            wait_for_complete = False

        if wait_for_complete == True:
            self.interrupt_func_register(self.arm_move_interrupt_callback)
            self.finished_func_register(self.arm_move_set_finished_callback)

            duss_result, identify = self.robotic_arm.robotic_arm_move_ctrl(
                1, rm_define.absolute_move, rm_define.mask_xy, 91, 41, 0, rm_define.TASK
            )

            if duss_result == rm_define.DUSS_SUCCESS:
                return get_task_dict(
                    identify, "task", (wait_for_complete), result=duss_result
                )
            else:
                self.interrupt_func_unregister()
                return get_task_dict(
                    None, "no_task", (wait_for_complete), result=duss_result
                )
        else:
            duss_result, resp = self.robotic_arm.robotic_arm_move_ctrl(
                1,
                rm_define.absolute_move,
                rm_define.mask_xy,
                91,
                41,
                0,
                rm_define.NO_TASK,
            )
            return get_task_dict(
                None, "action_immediate", (wait_for_complete), result=duss_result
            )

    def get_position(self):
        duss_result, resp = self.robotic_arm.get_robotic_arm_pos(1)
        if duss_result == rm_define.DUSS_SUCCESS:
            data = resp["data"]
            x_pos = tools.byte_to_int32(data[1:5])
            y_pos = tools.byte_to_int32(data[5:9])
            return [x_pos, y_pos]
        else:
            return [0, 0]

    def arm_move_interrupt_callback(self):
        self.stop()

    def arm_move_set_finished_callback(self):
        self.stop()

    def init(self):
        pass

    def stop(self):
        self.robotic_arm.robotic_arm_stop(1)
        logger.error("ROBOTICARM_CTRL: stop")

    def exit(self):
        logger.error("ROBOTICARM_CTRL: exit")


class SerialCtrl(RobotCtrlTool):
    baud_rate = 115200
    data_bit = "cs8"
    odd_even = "none"
    stop_bit = 1
    rx_enable = 1  # default disable serial rx
    tx_enable = 1  # default enable serial tx
    rx_buffer = 200
    tx_buffer = 200
    recv_buff_list = []

    def __init__(self, event_client):
        super().__init__(event_client)
        self.event_client = event_client
        self.serial = rm_module.Serial(event_client)
        self.condition_event_table = {}
        self.condition_event_time = {}
        self.condition_wait_event_list = {}
        self.recv_mutex = threading.Lock()

        self.sdk_data_process_callback = None

        self.event_init()

    def event_init(self):
        self.serial.recv_msg_register(self.serial_recv_process)
        self.serial.status_msg_register(self.serial_status_push)
        self.serial.set_serial_param(
            self.baud_rate,
            self.data_bit,
            self.odd_even,
            self.stop_bit,
            self.rx_enable,
            self.tx_enable,
            self.rx_buffer,
            self.tx_buffer,
        )

    def serial_recv_process(self, event_client, msg):
        logger.info("SERIAL_CTRL: SERIAL RECV PROCESS.")
        event_client.resp_ok(msg)
        data = msg["data"]
        msg_type = data[0]
        data_len = tools.byte_to_int16(data[1:3])  # (data[1] << 0x08) | data[2]
        logger.info(
            "msg_type is %d, data_len is %d, msg is :%s" % (msg_type, data_len, msg)
        )
        if msg_type == 1 and len(data) == (data_len + 3):
            self.recv_mutex.acquire()
            if len(self.recv_buff_list) > 200:
                self.recv_buff_list = []
            self.recv_buff_list += tools.byte_to_string(data[3:])
            self.recv_mutex.release()

            if self.sdk_data_process_callback:
                self.sdk_data_process_callback(tools.byte_to_string(data[3:]))

    # logger.error("serial_recv_process: recv_buff_list is:", self.recv_buff_list)

    def serial_status_push(self, event_client, msg):
        # logger.info('SERIAL_CTRL: SERIAL STATUS PUSH.')
        data = msg["data"]
        # logger.info("serial status is %s"%data)

    def clear_recv_buffer(self):
        self.recv_mutex.acquire()
        self.recv_buff_list = []
        self.recv_mutex.release()

    def serial_config(self, baud_rate, data_bit, odd_even, stop_bit):
        logger.error(
            "serial_config: baud_rate=%s, data_bit=%s, odd_even=%s, stop_bit=%s"
            % (baud_rate, data_bit, odd_even, stop_bit)
        )
        self.serial.set_serial_param(
            baud_rate,
            data_bit,
            odd_even,
            stop_bit,
            self.rx_enable,
            self.tx_enable,
            self.rx_buffer,
            self.tx_buffer,
        )

    def sdk_process_callback_register(self, callback):
        if callable(callback):
            self.sdk_data_process_callback = callback

    def sdk_process_callback_unregister(self):
        self.sdk_data_process_callback = None

    def write_line(self, string):
        str_len = len(string) + 1
        self.serial.send_msg(str_len, string + "\n")

    def send(self, fd, msg):
        self.write_line(msg)

    def write_string(self, string):
        str_len = len(string)
        self.serial.send_msg(str_len, string)

    def write_number(self, value):
        value_str = "%d" % value
        data_len = len(value_str)
        self.serial.send_msg(data_len, value_str)

    def write_value(self, str, value):
        value_str = "%d" % value
        data_len = len(str) + len(value_str) + 1
        self.serial.send_msg(data_len, str + ":" + value_str)

    def write_numbers(self, *var_list):
        numbers_string = ""
        for value in var_list:
            numbers_string += "%d" % value + ","
        data_len = len(numbers_string) - 1
        self.serial.send_msg(data_len, numbers_string[:-1])

    def read_line(self, timeout=-1):
        self.clear_recv_buffer()
        begin_time = time.time()
        index = 0
        while True:
            self.wait(50)
            if "\n" in self.recv_buff_list:
                index = self.recv_buff_list.index("\n")
                break
            if timeout == -1:
                continue
            if time.time() - begin_time > timeout:
                return ""
        read_string = self.recv_buff_list[: index + 1]
        self.clear_recv_buffer()
        return "".join(read_string)

    def read_string(self, timeout=-1):
        self.clear_recv_buffer()
        begin_time = time.time()
        index = 0
        while True:
            self.wait(50)
            if len(self.recv_buff_list):
                break
            if timeout == -1:
                continue
            if time.time() - begin_time > timeout:
                return ""
        read_string = self.recv_buff_list
        self.clear_recv_buffer()
        return "".join(read_string)

    def read_until(self, char, timeout=-1):
        if char not in ["\n", "$", "#", ".", ":", ";"]:
            raise Exception("char not in enum error")
        self.clear_recv_buffer()
        begin_time = time.time()
        index = 0
        while True:
            self.wait(50)
            if char in self.recv_buff_list:
                index = self.recv_buff_list.index(char)
                break
            if timeout == -1:
                continue
            if time.time() - begin_time > timeout:
                return ""

        read_string = self.recv_buff_list[: index + 1]
        self.clear_recv_buffer()
        return "".join(read_string)

    def stop(self):
        logger.error("SERIAL_CTRL: stop")
        self.serial.set_serial_param(
            self.baud_rate,
            self.data_bit,
            self.odd_even,
            self.stop_bit,
            0,
            0,
            self.rx_buffer,
            self.tx_buffer,
        )

    def exit(self):
        logger.error("SERIAL_CTRL: exit")
        self.stop()
        self.serial.recv_msg_unregister()
        self.serial.status_msg_unregister()

    def reset(self):
        self.serial.set_serial_param(
            self.baud_rate,
            self.data_bit,
            self.odd_even,
            self.stop_bit,
            self.rx_enable,
            self.tx_enable,
            self.rx_buffer,
            self.tx_buffer,
        )
