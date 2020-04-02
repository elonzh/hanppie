import operator
import random

import threading

from . import duml_cmdset
from . import duss_event_msg
from . import rm_define
from . import rm_log
from . import tools

logger = rm_log.dji_scratch_logger_get()


class RobotEvent(object):
    def __init__(self, task_identify, event_type, ctrl):
        self.task_identify = task_identify
        self.event_type = event_type
        self.ctrl = ctrl
        self.event_client = ctrl.event_client

        self.condition_wait_event = threading.Event()
        self.condition_wait_event.clear()

        self.watchdog_event = threading.Event()
        self.watchdog_lock = threading.Lock()
        self.watchdog_event.clear()

    def wait_for_complete(self, timeout=3600):
        if self.event_type == "task":
            task_finish, has_event = self.task_wait_for_complete(timeout)
        else:
            task_finish = True
            has_event = self.event_client.wait_for_event_process(
                self.ctrl.stop_with_interrupted
            )

        return task_finish, has_event

    def task_wait_for_complete(self, timeout):
        task_finish, has_event = False, False
        self.event_client.event_notify_register(self.task_identify, self)
        watchdog_retry_count = 8
        for i in range(int(timeout * 1000 / 50)):
            # interrupted by event
            if self.event_client.wait_for_event_process(
                self.ctrl.stop_with_interrupted
            ):
                has_event = True
                break
            # task finsihed
            if self.condition_wait_event.isSet():
                task_finish = True
                break

            ###################################################################
            # - block push package maybe be lost (percent package), will occure
            #   the function into timeout mode.
            # - add watch dog to monitor every percent package, if the package
            #   has been lost retry_count times lost, do task finish, break
            # - retry_count = (1/pusher_freq) / (1/check_wait_freq) * 2
            ###################################################################
            if self.watchdog_event.isSet():
                self.watchdog_lock.acquire()
                if self.watchdog_event.isSet():
                    self.watchdog_event.clear()
                    watchdog_retry_count = 8
                self.watchdog_lock.release()
            else:
                if watchdog_retry_count <= 0:
                    logger.error(
                        "SCRIPT_CTRL: task percent package lost some times, stopping task_sync"
                    )
                    task_finish = True
                    break
                else:
                    watchdog_retry_count -= 1

            if self.event_client.script_state.check_stop():
                self.event_client.script_state.reset_stop_flag()
                raise Exception("SCRIPT_CTRL: received exit cmd, raise exception")

            tools.wait(50)

        self.event_client.event_notify_unregister(self.task_identify)
        self.ctrl.stop_with_finish()
        self.ctrl.interrupt_func_unregister()
        self.ctrl.finished_func_unregister()
        return task_finish, has_event

    def notify_for_task_complete(self):
        self.condition_wait_event.set()

    def watchdog_set(self):
        self.watchdog_lock.acquire()
        self.watchdog_event.set()
        self.watchdog_lock.release()


class Chassis(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.chassis_id)
        self.msg_buff.set_default_moduleid(rm_define.chassis_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
        self.task_id = random.randint(duml_cmdset.TASK_ID_MIN, duml_cmdset.TASK_ID_MAX)
        self.speed_limit_flag = False

    def set_speed_limit_flag(self, flag):
        self.speed_limit_flag = flag

    def set_work_mode(self, mode):
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_WORK_MODE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_stick_overlay(self, enable_flag):
        self.msg_buff.init()
        self.msg_buff.append("enable", "uint8", enable_flag)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SPEED_MODE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_wheel_speed(self, speed1, speed2, speed3, speed4):
        speed1 = tools.data_limit(speed1, -1000, 1000)
        speed2 = tools.data_limit(speed2, -1000, 1000)
        speed3 = tools.data_limit(speed3, -1000, 1000)
        speed4 = tools.data_limit(speed4, -1000, 1000)
        self.msg_buff.init()
        self.msg_buff.append("speed1", "int16", speed1)
        self.msg_buff.append("speed2", "int16", speed2)
        self.msg_buff.append("speed3", "int16", speed3)
        self.msg_buff.append("speed4", "int16", speed4)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_WHEEL_SPEED_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_move_speed(self, speed_x, speed_y, speed_z):
        if self.speed_limit_flag:
            speed_x = tools.data_limit(speed_x, -1, 1)
            speed_y = tools.data_limit(speed_y, -1, 1)
            speed_z = tools.data_limit(speed_z, -250, 250)
        else:
            speed_x = tools.data_limit(speed_x, -3.5, 3.5)
            speed_y = tools.data_limit(speed_y, -3.5, 3.5)
            speed_z = tools.data_limit(speed_z, -600, 600)
        self.msg_buff.init()
        self.msg_buff.append("speed_x", "float", speed_x)
        self.msg_buff.append("speed_y", "float", speed_y)
        self.msg_buff.append("speed_z", "float", speed_z)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SPEED_SET
        # duss_result, resp = self.event_client.send_sync(self.msg_buff)
        # return duss_result
        self.msg_buff.cmd_type = duml_cmdset.NO_ACK_TYPE
        self.event_client.send_msg(self.msg_buff)
        return 0

    def set_pwm_value(self, mode, p):
        p = tools.data_limit(p, 0, 1000)
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.append("p0", "uint16", p)
        self.msg_buff.append("p1", "uint16", p)
        self.msg_buff.append("p2", "uint16", p)
        self.msg_buff.append("p3", "uint16", p)
        self.msg_buff.append("p4", "uint16", p)
        self.msg_buff.append("p5", "uint16", p)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SET_CHASSIS_PWM_VALUE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_pwm_freq(self, mode, p):
        p = tools.data_limit(p, 0, 50000)
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.append("p0", "uint16", p)
        self.msg_buff.append("p1", "uint16", p)
        self.msg_buff.append("p2", "uint16", p)
        self.msg_buff.append("p3", "uint16", p)
        self.msg_buff.append("p4", "uint16", p)
        self.msg_buff.append("p5", "uint16", p)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SET_CHASSIS_PWM_FREQ
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_follow_speed(self, speed_x, speed_y, angle):
        if self.speed_limit_flag:
            speed_x = tools.data_limit(speed_x, -1, 1)
            speed_y = tools.data_limit(speed_y, -1, 1)
            angle = tools.data_limit(angle, -250, 250)
        else:
            speed_x = tools.data_limit(speed_x, -3.5, 3.5)
            speed_y = tools.data_limit(speed_y, -3.5, 3.5)
            angle = tools.data_limit(angle, -600, 600)
        self.msg_buff.init()
        self.msg_buff.append("speed_x", "float", speed_x)
        self.msg_buff.append("speed_y", "float", speed_y)
        self.msg_buff.append("angle", "float", angle)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_FOLLOW_MODE_SET
        # duss_result, resp = self.event_client.send_sync(self.msg_buff)
        # return duss_result
        self.msg_buff.cmd_type = duml_cmdset.NO_ACK_TYPE
        self.event_client.send_msg(self.msg_buff)
        return 0

    def set_position_cmd(
        self,
        ctrl_mode,
        axis_mode,
        pos_x,
        pos_y,
        angle_yaw,
        vel_xy,
        alg_omg,
        cmd_type=rm_define.NO_TASK,
    ):
        pos_x = tools.data_limit(pos_x, -500, 500)
        pos_y = tools.data_limit(pos_y, -500, 500)
        angle_yaw = tools.data_limit(angle_yaw, -18000, 18000)
        vel_xy = tools.data_limit(vel_xy, 10, 250)
        alg_omg = tools.data_limit(alg_omg, 100, 5400)

        self.msg_buff.init()

        self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
        task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)

        self.msg_buff.append("ctrl_mode", "uint8", ctrl_mode)
        self.msg_buff.append("axis_mode", "uint8", axis_mode)
        self.msg_buff.append("pos_x", "int16", pos_x)
        self.msg_buff.append("pos_y", "int16", pos_y)
        self.msg_buff.append("angle_yaw", "int16", angle_yaw)
        self.msg_buff.append("vel_xy_max", "uint8", vel_xy)
        self.msg_buff.append("agl_omg_max", "uint16", alg_omg)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_POSITION_SET

        if cmd_type == rm_define.TASK:
            self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
            self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_POSITION_SET

            event_task = {}
            event_task["task_id"] = self.task_id
            event_task["receiver"] = self.msg_buff.receiver
            event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
            event_task["cmd_id"] = duml_cmdset.DUSS_MB_CMD_RM_CHASSIS_POSITION_TASK_PUSH
            event_task[
                "no_task_msg_cmd_id"
            ] = duml_cmdset.DUSS_MB_CMD_RM_CHASSIS_POSITION_TASK_PUSH

            duss_result, identify = self.event_client.send_task_async(
                self.msg_buff, event_task
            )
            return duss_result, identify
        else:
            duss_result, resp = self.event_client.send_sync(self.msg_buff)
            return duss_result, resp

    # scratch not support now
    def set_data_mode(self, speed_max, speed_yaw_max):
        self.msg_buff.init()
        self.msg_buff.append("speed_max", "float", speed_max)
        self.msg_buff.append("speed_yaw_max", "float", speed_yaw_max)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_MODE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_ground_mode(self, speed_x, speed_y, speed_z, body_angle):
        self.msg_buff.init()
        self.msg_buff.append("speed_x", "float", speed_x)
        self.msg_buff.append("speed_y", "float", speed_y)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GROUND_MODE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_position_cmd_stop(self):
        task_ctrl = duml_cmdset.TASK_CTRL_STOP
        self.msg_buff.init()
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append("ctrl_mode", "uint8", 0)
        self.msg_buff.append("axis_mode", "uint8", 0)
        self.msg_buff.append("pos_x", "int16", 0)
        self.msg_buff.append("pos_y", "int16", 0)
        self.msg_buff.append("angle_yaw", "int16", 0)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_POSITION_SET

        duss_result = self.event_client.send_task_stop(self.msg_buff, 3)
        return duss_result

    def sub_attitude_speed_position_info(self, bag_id, uuid_list, callback):
        uuid_list = [
            0x0C000000,  # esc_info
            0x0E000000,  # ns_pos
            0x0F000000,  # ns_vel
            0x11000000,  # ns_imu
            0x14000000,  # attitude_info
            0x12000000,  # ns_sa_status
        ]

        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_FC << 8
            | duml_cmdset.DUSS_MB_CMD_FC_SUB_SERVICE_RSP
        )
        self.event_client.async_req_register(cmd_set_id, callback)

        self.msg_buff.init()
        self.msg_buff.append("bag_id", "uint8", 0)
        self.msg_buff.append("freq", "uint16", 20)
        self.msg_buff.append("conf_flag", "uint8", 0)
        self.msg_buff.append("data_num", "uint8", len(uuid_list))
        for index, value in enumerate(uuid_list):
            self.msg_buff.append("uuid" + str(index), "uint32", value)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_FC
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_FC_SUB_SERVICE_REQ

        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result != duml_cmdset.DUSS_MB_RET_OK:
            logger.error(
                "CHASSIS: error in sub_attitude_speed_position_info(), ret code = "
                + str(duss_result)
            )
            self.event_client.async_req_unregister(cmd_set_id)
        return duss_result

    def unsub_attitude_speed_position_info(self, bag_id):
        self.msg_buff.init()
        self.msg_buff.append("bag_id", "uint8", bag_id)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_FC
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_FC_SUB_REMOVE_SERVICE_REQ
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result == duml_cmdset.DUSS_MB_RET_OK:
            cmd_set_id = (
                duml_cmdset.DUSS_MB_CMDSET_FC << 8
                | duml_cmdset.DUSS_MB_CMD_FC_SUB_SERVICE_RSP
            )
            self.event_client.async_req_unregister(cmd_set_id)
        else:
            logger.error(
                "CHASSIS: error in unsub_attitude_speed_position_info(), ret code = "
                + str(duss_result)
            )
        return duss_result

    def unsub_all_info(self):
        self.msg_buff.init()
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_FC
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_FC_SUB_RESET_SERVICE_REQ
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result == duml_cmdset.DUSS_MB_RET_OK:
            cmd_set_id = (
                duml_cmdset.DUSS_MB_CMDSET_FC << 8
                | duml_cmdset.DUSS_MB_CMD_FC_SUB_SERVICE_RSP
            )
            self.event_client.async_req_unregister(cmd_set_id)
        else:
            logger.error(
                "CHASSIS: error in unsub_all_attitude_speed_position_info(), ret code = "
                + str(duss_result)
            )
        return duss_result

    def attitude_event_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_ATTITUDE_EVENT
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def attitude_event_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_ATTITUDE_EVENT
        )
        self.event_client.async_req_unregister(cmd_set_id)


class Gun:
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.gun_id)
        self.msg_buff.set_default_moduleid(rm_define.gun_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
        self.cmd_mutex = threading.Lock()

    def set_cmd_fire(self, mode, count):
        self.cmd_mutex.acquire()
        cmd = ((mode << 4) & 0xF0) + (count & 0x0F)
        self.msg_buff.init()
        self.msg_buff.append("cmd", "uint8", cmd)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SHOOT_CMD
        self.msg_type = duml_cmdset.REQ_PKG_TYPE
        self.msg_buff.receiver = rm_define.hdvt_uav_id
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        self.cmd_mutex.release()
        return duss_result

    def set_led(self, mode, ctrl):
        self.cmd_mutex.acquire()
        self.msg_buff.init()
        ctrl_mode = ((ctrl << 4) & 0xF0) + (mode & 0x0F)
        self.msg_buff.append("ctrl_mode", "uint8", ctrl_mode)
        self.msg_buff.append("r", "uint8", 255)
        self.msg_buff.append("g", "uint8", 255)
        self.msg_buff.append("b", "uint8", 255)
        self.msg_buff.append("times", "uint8", 1)
        self.msg_buff.append("t1", "uint16", 100)
        self.msg_buff.append("t2", "uint16", 100)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GUN_LED_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        self.cmd_mutex.release()
        return duss_result


class Gimbal(object):
    def __init__(self, event_client):
        self.accel_contral_flag = 0xDC
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.gimbal_id)
        self.msg_buff.set_default_moduleid(rm_define.gimbal_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_GIMBAL)
        self.task_id = random.randint(duml_cmdset.TASK_ID_MIN, duml_cmdset.TASK_ID_MAX)

    def set_work_mode(self, mode):
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.append("cmd", "uint8", 0)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_SET_MODE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_stick_overlay(self, enable):
        if enable == rm_define.stick_overlay_enable:
            self.accel_contral_flag = self.accel_contral_flag & ~(1 << 6)
        else:
            self.accel_contral_flag = self.accel_contral_flag | (1 << 6)
        return rm_define.SUCCESS

    def return_middle(
        self, axis_maskbit, max_yaw_accel, max_roll_accel, max_pitch_accel
    ):
        max_yaw_accel = tools.data_limit(max_yaw_accel, 5, 540)
        max_roll_accel = 0
        max_pitch_accel = tools.data_limit(max_pitch_accel, 5, 540)
        self.msg_buff.init()
        self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
        task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append(
            "axis_maskbit", "uint8", axis_maskbit
        )  # all axis reset position
        self.msg_buff.append("max_yaw_accel", "uint16", max_yaw_accel)
        self.msg_buff.append("max_roll_accel", "uint16", max_roll_accel)
        self.msg_buff.append("max_pitch_accel", "uint16", max_pitch_accel)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_RESET_POSITION_SET

        event_task = {}
        event_task["task_id"] = self.task_id
        event_task["receiver"] = self.msg_buff.receiver
        event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
        event_task["cmd_id"] = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_POSITION_TASK_PUSH

        duss_result, identify = self.event_client.send_task_async(
            self.msg_buff, event_task
        )
        return duss_result, identify

    def set_degree_ctrl(
        self,
        pitch_degree,
        yaw_degree,
        pitch_accel,
        yaw_accel,
        axis_maskbit,
        coodrdinate,
        deviation=rm_define.gimbal_deviation,
        cmd_type=rm_define.NO_TASK,
    ):
        yaw_accel = tools.data_limit(
            yaw_accel,
            rm_define.gimbal_rotate_speed_min,
            rm_define.gimbal_rotate_speed_max,
        )
        pitch_accel = tools.data_limit(
            pitch_accel,
            rm_define.gimbal_rotate_speed_min,
            rm_define.gimbal_rotate_speed_max,
        )
        self.msg_buff.init()

        self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
        task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)

        ctrl = ((coodrdinate & 0x07) << 3) | axis_maskbit
        self.msg_buff.append("ctrl", "uint8", ctrl)
        self.msg_buff.append("yaw_degree", "int16", yaw_degree)
        self.msg_buff.append("roll_degree", "int16", 0)
        self.msg_buff.append("pitch_degree", "int16", pitch_degree)
        self.msg_buff.append("deviation", "int16", deviation)
        self.msg_buff.append("yaw_accel", "uint16", yaw_accel)
        self.msg_buff.append("roll_accel", "uint16", 0)
        self.msg_buff.append("pitch_accel", "uint16", pitch_accel)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_DEGREE_SET

        if cmd_type == rm_define.TASK:
            self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
            self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_DEGREE_SET

            event_task = {}
            event_task["task_id"] = self.task_id
            event_task["receiver"] = self.msg_buff.receiver
            event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
            event_task["cmd_id"] = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_POSITION_TASK_PUSH
            duss_result, identify = self.event_client.send_task_async(
                self.msg_buff, event_task
            )
            return duss_result, identify
        else:
            duss_result, resp = self.event_client.send_sync(self.msg_buff)
            return duss_result, resp

    def set_accel_ctrl(self, pitch_accel, yaw_accel, contral=None):
        if contral == None:
            contral = self.accel_contral_flag
        pitch_accel = tools.data_limit(pitch_accel, -540, 540)
        yaw_accel = tools.data_limit(yaw_accel, -540, 540)

        pitch_accel = pitch_accel * 10
        yaw_accel = yaw_accel * 10
        pitch_accel = tools.data_limit(
            pitch_accel,
            rm_define.gimbal_pitch_accel_min,
            rm_define.gimbal_pitch_accel_max,
        )
        yaw_accel = tools.data_limit(
            yaw_accel, rm_define.gimbal_yaw_accel_min, rm_define.gimbal_yaw_accel_max
        )

        self.msg_buff.init()
        self.msg_buff.append("yaw_accel", "int16", yaw_accel)
        self.msg_buff.append("roll_accel", "int16", 0)
        self.msg_buff.append("pitch_accel", "int16", pitch_accel)
        self.msg_buff.append("contral", "uint8", contral)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_EXT_CTRL_ACCEL
        # duss_result, resp = self.event_client.send_sync(self.msg_buff)
        # return duss_result
        self.msg_buff.cmd_type = duml_cmdset.NO_ACK_TYPE
        self.event_client.send_msg(self.msg_buff)
        return 0

    def set_compound_motion_ctrl(
        self, test_flag, enable_flag, axis, phase, cycle, margin, times
    ):
        ctrl_flag = 0x00
        ctrl_flag = (
            ctrl_flag | test_flag | enable_flag << 1 | axis << 2 | phase << 6 | 1 << 7
        )
        margin = tools.data_limit(margin, 0, 2700)
        self.msg_buff.init()
        self.msg_buff.append("id", "uint8", 0x0D)
        self.msg_buff.append("len", "uint8", 6)
        self.msg_buff.append("ctrl_flag", "uint8", ctrl_flag)
        self.msg_buff.append("cycle", "uint16", cycle)
        self.msg_buff.append("margin", "uint16", margin)
        self.msg_buff.append("times", "uint8", times)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_ROTATE_EXP_CMD
        self.msg_buff.get_data()

        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_suspend_resume(self, state):
        cmd = 0x00
        if state == rm_define.gimbal_suspend:
            cmd = 0x2AB5
        elif state == rm_define.gimbal_resume:
            cmd = 0x7EF2

        self.msg_buff.init()
        self.msg_buff.append("cmd", "uint16", cmd)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_SUSPEND_RESUME
        self.msg_buff.cmd_type = (
            duml_cmdset.REQ_PKG_TYPE | duml_cmdset.NEED_ACK_NO_FINISH_TYPE
        )

        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def sub_attitude_info(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_GIMBAL << 8
            | duml_cmdset.DUSS_MB_CMD_GIMBAL_PUSH_POSITION
        )
        self.event_client.async_req_register(cmd_set_id, callback)
        self.msg_buff.init()
        self.msg_buff.append(
            "cmd", "uint8", duml_cmdset.DUSS_MB_CMD_GIMBAL_PUSH_POSITION
        )
        self.msg_buff.append("other", "uint8", 0xFF)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_DEGREE_INFO_SUBSCRIPTION
        self.msg_buff.cmd_type = duml_cmdset.REQ_PKG_TYPE | duml_cmdset.NEED_ACK_TYPE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)

        if duss_result != duml_cmdset.DUSS_MB_RET_OK:
            logger.error(
                "GIMBAL: error in sub_attitude_info(), ret code = " + str(duss_result)
            )
            self.event_client.async_req_unregister(cmd_set_id)

    def unsub_attitude_info(self):
        # TODO no unsub attitude info msg to gimbal.
        self.msg_buff.init()
        self.msg_buff.append(
            "cmd", "uint8", duml_cmdset.DUSS_MB_CMD_GIMBAL_PUSH_POSITION
        )
        self.msg_buff.append("other", "uint8", 0)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GIMBAL_DEGREE_INFO_SUBSCRIPTION
        self.msg_buff.cmd_type = duml_cmdset.REQ_PKG_TYPE | duml_cmdset.NEED_ACK_TYPE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result == duml_cmdset.DUSS_MB_RET_OK:
            cmd_set_id = (
                duml_cmdset.DUSS_MB_CMDSET_GIMBAL << 8
                | duml_cmdset.DUSS_MB_CMD_GIMBAL_PUSH_POSITION
            )
            self.event_client.async_req_unregister(cmd_set_id)

    def set_degree_ctrl_stop(self):
        task_ctrl = duml_cmdset.TASK_CTRL_STOP
        self.msg_buff.init()
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append("ctrl", "uint8", 0)
        self.msg_buff.append("yaw_degree", "int16", 0)
        self.msg_buff.append("roll_degree", "int16", 0)
        self.msg_buff.append("pitch_degree", "int16", 0)
        self.msg_buff.append("deviation", "int16", 0)
        self.msg_buff.append("yaw_accel", "uint16", 0)
        self.msg_buff.append("roll_accel", "uint16", 0)
        self.msg_buff.append("pitch_accel", "uint16", 0)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_DEGREE_SET

        duss_result = self.event_client.send_task_stop(self.msg_buff)
        return duss_result

    def return_middle_stop(self):
        task_ctrl = duml_cmdset.TASK_CTRL_STOP
        self.msg_buff.init()
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append("axis_maskbit", "uint8", 0)
        self.msg_buff.append("max_yaw_accel", "uint16", 0)
        self.msg_buff.append("max_roll_accel", "uint16", 0)
        self.msg_buff.append("max_pitch_accel", "uint16", 0)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GIMBAL_RESET_POSITION_SET

        duss_result = self.event_client.send_task_stop(self.msg_buff)
        return duss_result


class Led(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.hdvt_uav_id)
        self.msg_buff.set_default_moduleid(rm_define.system_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
        self.led_state = {}
        self._set_led_default_state()

    def set_led(self, comp, mode, ctrl, r, g, b, count, t1, t2, marquee=False):
        duss_result = rm_define.DUSS_SUCCESS
        control_mask = 0
        force_update_mask_flag = False
        if marquee:
            force_update_mask_flag = True
            led_mask = 0x0F
        else:
            led_mask = 0xFF

        pos_list = [
            rm_define.armor_top_left,
            rm_define.armor_top_right,
            rm_define.armor_bottom_front,
            rm_define.armor_bottom_back,
            rm_define.armor_bottom_left,
            rm_define.armor_bottom_right,
        ]
        update_states = {
            "mode": mode,
            "ctrl": ctrl,
            "r": r,
            "g": g,
            "b": b,
            "count": count,
            "t1": t1,
            "t2": t2,
            "mask": led_mask,
        }
        control_mask, led_state = self._update_states(comp, pos_list, update_states)
        self._update_top_states(comp, mode)

        if force_update_mask_flag:
            led_state["mask"] = update_states["mask"]
        if control_mask != 0:
            duss_result = self._send_led_cmd(control_mask, led_state)
        return duss_result

    def set_single_led(self, comp, led_mask, effect, **kw):
        duss_result = rm_define.DUSS_SUCCESS
        control_mask = 0
        if comp & rm_define.armor_top_left:
            led_state = dict(self.led_state[rm_define.armor_top_left])
            led_state["mode"] = 1
            if effect == rm_define.effect_always_on:
                led_state["mask"] |= led_mask
            elif effect == rm_define.effect_always_off:
                led_state["mask"] &= ~led_mask
            if not operator.eq(led_state, self.led_state[rm_define.armor_top_left]):
                self.led_state[rm_define.armor_top_left] = led_state
                control_mask = control_mask | rm_define.armor_top_left

        if comp & rm_define.armor_top_right:
            led_state = dict(self.led_state[rm_define.armor_top_right])
            led_state["mode"] = 1
            if effect == rm_define.effect_always_on:
                led_state["mask"] |= led_mask
            elif effect == rm_define.effect_always_off:
                led_state["mask"] &= ~led_mask
            if not operator.eq(led_state, self.led_state[rm_define.armor_top_right]):
                self.led_state[rm_define.armor_top_right] = led_state
                control_mask = control_mask | rm_define.armor_top_right

        if control_mask != 0:
            duss_result = self._send_led_cmd(control_mask, led_state, **kw)
        return duss_result

    def set_flash(self, comp, freq):
        duss_result = rm_define.DUSS_SUCCESS
        interval = int(500 / freq)
        pos_list = [
            rm_define.armor_top_left,
            rm_define.armor_top_right,
            rm_define.armor_bottom_front,
            rm_define.armor_bottom_back,
            rm_define.armor_bottom_left,
            rm_define.armor_bottom_right,
        ]

        update_states = {"mode": 3, "t1": interval, "t2": interval}
        for pos in pos_list:
            if pos & comp != 0:
                control_mask, led_state = self._update_states(
                    pos, pos_list, update_states
                )
                self._update_top_states(comp, 3)

                if control_mask != 0:
                    result = self._send_led_cmd(control_mask, led_state)
                    if result != rm_define.DUSS_SUCCESS:
                        duss_result = result
        return duss_result

    def _set_led_default_state(self):
        default_states = {
            "mode": 0,
            "ctrl": 0,
            "r": 0,
            "g": 0,
            "b": 0,
            "count": 0,
            "t1": 0,
            "t2": 0,
            "mask": 0,
        }
        led_state = default_states
        self.led_state[rm_define.armor_top_left] = led_state
        self.led_state[rm_define.armor_top_right] = led_state
        self.led_state[rm_define.armor_bottom_front] = led_state
        self.led_state[rm_define.armor_bottom_back] = led_state
        self.led_state[rm_define.armor_bottom_left] = led_state
        self.led_state[rm_define.armor_bottom_right] = led_state

    def _update_states(self, comp, pos_list, update_state):
        control_mask = 0
        led_state = {}
        for pos in pos_list:
            if comp & pos:
                led_state = dict(self.led_state[pos])
                for state in update_state.keys():
                    led_state[state] = update_state[state]
                if not operator.eq(led_state, self.led_state[pos]):
                    self.led_state[pos] = led_state
                    control_mask = control_mask | pos
        return control_mask, led_state

    def _update_top_states(self, comp, ctrl):
        if comp & rm_define.armor_top_left:
            if ctrl == 0:
                self.led_state[rm_define.armor_top_left]["mask"] = 0x00
            else:
                self.led_state[rm_define.armor_top_left]["mask"] = 0xFF
        if comp & rm_define.armor_top_right:
            if ctrl == 0:
                self.led_state[rm_define.armor_top_right]["mask"] = 0x00
            else:
                self.led_state[rm_define.armor_top_right]["mask"] = 0xFF

    def _send_led_cmd(self, comp, led_state, **kw):
        ctrl_mode = ((led_state["ctrl"] << 4) & 0xF0) + (led_state["mode"] & 0x0F)

        r = led_state["r"]
        g = led_state["g"]
        b = led_state["b"]
        if "r" in kw.keys():
            r = kw["r"]
        if "g" in kw.keys():
            g = kw["g"]
        if "b" in kw.keys():
            b = kw["b"]
        self.msg_buff.init()
        self.msg_buff.append("led_idx", "uint32", comp)
        self.msg_buff.append("led_mask", "uint16", led_state["mask"])
        self.msg_buff.append("ctrl_mode", "uint8", ctrl_mode)
        self.msg_buff.append("r", "uint8", r)
        self.msg_buff.append("g", "uint8", g)
        self.msg_buff.append("b", "uint8", b)
        self.msg_buff.append("count", "uint8", led_state["count"])
        self.msg_buff.append("t1", "uint16", led_state["t1"])
        self.msg_buff.append("t2", "uint16", led_state["t2"])
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SYSTEM_LED_SET
        self.msg_buff.cmd_type = duml_cmdset.REQ_PKG_TYPE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def set_gun_led(self, mode, enable_flag):
        self.msg_buff.init()
        enable_flag &= 0x01
        ctrl_mode = ((mode << 4) & 0xF0) | enable_flag
        self.msg_buff.append("led_idx", "uint32", 1 << 6)
        self.msg_buff.append("led_mask", "uint16", 0xFF)
        self.msg_buff.append("ctrl_mode", "uint8", ctrl_mode)
        self.msg_buff.append("r", "uint8", 255)
        self.msg_buff.append("g", "uint8", 255)
        self.msg_buff.append("b", "uint8", 255)
        self.msg_buff.append("count", "uint8", 100)
        self.msg_buff.append("t1", "uint16", 1)
        self.msg_buff.append("t2", "uint16", 1)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SYSTEM_LED_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result


class Armor(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.armor_id)
        self.msg_buff.set_default_moduleid(rm_define.armor_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def hit_event_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_HIT_EVENT
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def ir_event_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_IR_EVENT
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def hit_event_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_HIT_EVENT
        )
        self.event_client.async_req_unregister(cmd_set_id)

    def hit_event_query(self):
        self.msg_buff.init()
        self.msg_buff.append(
            "type", "uint8", rm_define.local_service_query_type_armor_hit
        )
        self.msg_buff.receiver = rm_define.scratch_sys_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_LOCAL_SUB_SERVICE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_hit_sensitivity(self, k):
        peak_min = int(160 * k)
        peak_ave = int(180 * k)
        final_peak = int(200 * k)
        self.msg_buff.init()
        self.msg_buff.append("set_type", "uint8", 0x3F)
        self.msg_buff.append("voice_energy_entry", "uint16", 500)
        self.msg_buff.append("voice_energy_exit", "uint16", 300)
        self.msg_buff.append("voice_len_max", "uint16", 50)
        self.msg_buff.append("voice_len_min", "uint16", 13)
        self.msg_buff.append("voice_silence_wait_limit", "uint16", 6)
        self.msg_buff.append("voice_peak_count_min", "uint16", 1)
        self.msg_buff.append("voice_multi_peak_min", "uint16", peak_min)
        self.msg_buff.append("voice_multi_peak_ave", "uint16", peak_ave)
        self.msg_buff.append("voice_multi_final_peak", "uint16", final_peak)

        self.msg_buff.receiver = rm_define.hdvt_uav_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_ARMOR_VOICE_PARAMS_SET

        self.event_client.send_msg(self.msg_buff)
        return rm_define.DUSS_SUCCESS


class Vision(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.vision_id)
        self.msg_buff.set_default_moduleid(rm_define.system_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_VISION)

    def vision_sdk_enable(self, detection_class):
        self.msg_buff.init()
        self.msg_buff.append("detection_class", "uint16", detection_class)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_ENABLE_SDK_FUNC
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def vision_get_sdk_func(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_GET_SDK_FUNC
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        cur_sdk_func_mask = 0
        if duss_result == rm_define.DUSS_SUCCESS:
            cur_sdk_func_mask = tools.byte_to_uint16(resp["data"][1:3])
        return duss_result, cur_sdk_func_mask

    def recognition_event_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_VISION << 8
            | duml_cmdset.DUSS_MB_CMD_VISION_DETECTION_MSG_PUSH
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def recognition_event_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_VISION << 8
            | duml_cmdset.DUSS_MB_CMD_VISION_DETECTION_MSG_PUSH
        )
        self.event_client.async_req_unregister(cmd_set_id)

    # need to del start...
    def ctrl_param_set(self, ctrl_object, ctrl_amount, ctrl_max_speed, kp, ki=0, kd=0):
        self.msg_buff.init()
        self.msg_buff.append("ctrl_object", "uint8", ctrl_object)
        self.msg_buff.append("ctrl_amount", "float", ctrl_amount)
        self.msg_buff.append("ctrl_max_speed", "float", ctrl_max_speed)
        self.msg_buff.append("kp", "float", kp)
        self.msg_buff.append("ki", "float", ki)
        self.msg_buff.append("kd", "float", kd)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_CTRL_PARAM_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    # need to del end...

    def chassis_ctrl_param_set(
        self, robot_mode, x_ctrl_dict, y_ctrl_dict, yaw_ctrl_dict
    ):
        self.msg_buff.init()
        self.msg_buff.append("robot_mode", "uint8", robot_mode)

        self.msg_buff.append("x_ctrl_amount", "float", x_ctrl_dict["amount"])
        self.msg_buff.append("x_kp", "float", x_ctrl_dict["kp"])
        self.msg_buff.append("x_ki", "float", x_ctrl_dict["ki"])
        self.msg_buff.append("x_kd", "float", x_ctrl_dict["kd"])
        self.msg_buff.append("x_ctrl_max_speed", "float", x_ctrl_dict["max_speed"])

        self.msg_buff.append("y_ctrl_amount", "float", y_ctrl_dict["amount"])
        self.msg_buff.append("y_kp", "float", y_ctrl_dict["kp"])
        self.msg_buff.append("y_ki", "float", y_ctrl_dict["ki"])
        self.msg_buff.append("y_kd", "float", y_ctrl_dict["kd"])
        self.msg_buff.append("y_ctrl_max_speed", "float", y_ctrl_dict["max_speed"])

        self.msg_buff.append("yaw_ctrl_amount", "float", yaw_ctrl_dict["amount"])
        self.msg_buff.append("yaw_kp", "float", yaw_ctrl_dict["kp"])
        self.msg_buff.append("yaw_ki", "float", yaw_ctrl_dict["ki"])
        self.msg_buff.append("yaw_kd", "float", yaw_ctrl_dict["kd"])
        self.msg_buff.append("yaw_ctrl_max_speed", "float", yaw_ctrl_dict["max_speed"])

        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_CHASSIS_CTRL_PARAM_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def gimbal_ctrl_param_set(self, robot_mode, yaw_ctrl_dict, pitch_ctrl_dict):
        self.msg_buff.init()
        self.msg_buff.append("robot_mode", "uint8", robot_mode)

        self.msg_buff.append("yaw_ctrl_amount", "float", yaw_ctrl_dict["amount"])
        self.msg_buff.append("yaw_kp", "float", yaw_ctrl_dict["kp"])
        self.msg_buff.append("yaw_ki", "float", yaw_ctrl_dict["ki"])
        self.msg_buff.append("yaw_kd", "float", yaw_ctrl_dict["kd"])
        self.msg_buff.append("yaw_ctrl_max_speed", "float", yaw_ctrl_dict["max_speed"])

        self.msg_buff.append("pitch_ctrl_amount", "float", pitch_ctrl_dict["amount"])
        self.msg_buff.append("pitch_kp", "float", pitch_ctrl_dict["kp"])
        self.msg_buff.append("pitch_ki", "float", pitch_ctrl_dict["ki"])
        self.msg_buff.append("pitch_kd", "float", pitch_ctrl_dict["kd"])
        self.msg_buff.append(
            "pitch_ctrl_max_speed", "float", pitch_ctrl_dict["max_speed"]
        )

        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_GIMBAL_CTRL_PARAM_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def detection_attr_set(self, attr, value):
        self.msg_buff.init()
        self.msg_buff.append("attr", "uint8", attr)
        self.msg_buff.append("value", "uint8", value)
        self.msg_buff.receiver = rm_define.hdvt_uav_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VISION_LINE_DETECTION_ATTR_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result


class Media(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.camera_id)
        self.msg_buff.set_default_moduleid(rm_define.camera_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_CAMERA)
        self.task_id = 0
        self.err_code = 0

    def set_camera_mode(self, mode):
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_SET_WORKMODE
        duss_result, resp = self.event_client.send_sync(self.msg_buff, 0.5)
        return duss_result

    def set_camera_ev(self, ev):
        self.msg_buff.init()
        self.msg_buff.append("ev", "uint8", ev)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_SET_SCENE_MODE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_camera_zv(self, zv):
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", 0x09)
        self.msg_buff.append("oz", "uint16", 0x00)
        self.msg_buff.append("dz", "uint16", zv)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_SET_ZOOM_PARAM
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def get_camera_brightness(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_MEDIA_CAMERA_BRIGHTNESS_GET
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def get_sight_bead_position(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GET_SIGHT_BEAD_POSITION
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.receiver = rm_define.hdvt_uav_id
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def capture(self):
        retry = 0
        self.msg_buff.init()
        self.msg_buff.append("type", "uint8", 1)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_CAPTURE
        while retry < 5:
            duss_result, resp = self.event_client.send_sync(self.msg_buff, 0.5)
            if duss_result == rm_define.DUSS_SUCCESS:
                if resp["data"][0] == 0:
                    return rm_define.DUSS_SUCCESS
                else:
                    self.err_code = resp["data"][0]
                    logger.error("MEDIA: capture error = " + str(hex(resp["data"][0])))
                    tools.wait(200)
            retry = retry + 1
        return rm_define.DUSS_ERR_FAILURE

    def enable_sound_recognition(self, enable_flag, func_mask):
        self.msg_buff.init()
        self.msg_buff.append("enable", "uint8", enable_flag)
        self.msg_buff.append("func_mask", "uint8", func_mask)
        self.msg_buff.append("reserve", "uint8", 0)
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_MEDIA_SOUND_RECOGNIZE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def recognition_push_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_MEDIA_SOUND_RECOGNIZE_PUSH
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def recognition_push_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_MEDIA_SOUND_RECOGNIZE_PUSH
        )
        self.event_client.async_req_unregister(cmd_set_id)

    def record(self, ctrl):
        retry = 0
        self.msg_buff.init()
        self.msg_buff.append("ctrl", "uint8", ctrl)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RECORD
        while retry < 5:
            duss_result, resp = self.event_client.send_sync(self.msg_buff, 0.5)
            if duss_result == rm_define.DUSS_SUCCESS:
                if resp["data"][0] == 0:
                    return rm_define.DUSS_SUCCESS
                else:
                    self.err_code = resp["data"][0]
                    logger.error("MEDIA: capture error = " + str(hex(resp["data"][0])))
                    tools.wait(200)
            retry = retry + 1
        return rm_define.DUSS_ERR_FAILURE

    def play_sound(self, id):
        ctrl = 1
        if (
            id >= rm_define.media_sound_solmization_1C
            and id <= rm_define.media_sound_solmization_3B
        ):
            ctrl = 2
        self.msg_buff.init()
        self.msg_buff.append("id", "uint32", id)
        self.msg_buff.append("ctrl", "uint8", ctrl)
        # TODO interval value
        self.msg_buff.append("interval", "uint16", 5000)
        self.msg_buff.append("times", "uint8", 1)

        self.msg_buff.receiver = rm_define.hdvt_uav_id
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_PLAY_SOUND

        duss_result, resp = self.event_client.send_sync(self.msg_buff)

        return duss_result

    def play_sound_task(self, id):
        ctrl = 1
        if (
            id >= rm_define.media_sound_solmization_1C
            and id <= rm_define.media_sound_solmization_3B
        ):
            ctrl = 2
        self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
        task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
        self.msg_buff.init()
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append("id", "uint32", id)
        self.msg_buff.append("ctrl", "uint8", ctrl)
        # TODO interval value
        self.msg_buff.append("interval", "uint16", 5000)
        self.msg_buff.append("times", "uint8", 1)

        self.msg_buff.receiver = rm_define.hdvt_uav_id
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_PLAY_SOUND_TASK

        event_task = {}
        event_task["task_id"] = self.task_id
        event_task["receiver"] = self.msg_buff.receiver
        event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
        event_task["cmd_id"] = duml_cmdset.DUSS_MB_CMD_RM_PLAY_SOUND_TASK_PUSH

        duss_result, identify = self.event_client.send_task_async(
            self.msg_buff, event_task
        )
        return duss_result, identify

    def play_sound_task_stop(self):
        task_ctrl = duml_cmdset.TASK_CTRL_STOP
        self.msg_buff.init()
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)

        self.msg_buff.append("id", "uint16", 0)
        self.msg_buff.append("ctrl", "uint8", 0)
        # TODO interval value
        self.msg_buff.append("interval", "uint16", 0)
        self.msg_buff.append("times", "uint8", 0)

        self.msg_buff.receiver = rm_define.hdvt_uav_id
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_PLAY_SOUND_TASK

        duss_result = self.event_client.send_task_stop(self.msg_buff, 3)
        return duss_result

    def get_err_code(self):
        return self.err_code


class Debug(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.hdvt_uav_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def test1(self, arg1):
        self.msg_buff.init()
        duss_result = rm_define.DUSS_SUCCESS
        if arg1 == 1:
            self.msg_buff.receiver = rm_define.gun_id
            self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_COMMON
            self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_GET_DEVICE_VERSION
            duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def test2(self, arg1, arg2):
        logger.info("%s %s" % (arg1, arg2))
        return True

    def test3(self, arg1, arg2, arg3):
        logger.info("%s %s %s" % (arg1, arg2, arg3))
        return True

    def test4(self, arg1, arg2, arg3, arg4):
        logger.info("%s %s %s %s" % (arg1, arg2, arg3, arg4))
        return True


class Mobile(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.mobile_id)
        self.msg_buff.set_default_moduleid(rm_define.system_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def custom_msg_send(self, msg_type, msg_level, msg):
        msg_string = tools.string_limit(msg, rm_define.custom_msg_max_len)
        self.msg_buff.init()
        self.msg_buff.append("msg_type", "uint8", msg_type)
        self.msg_buff.append("msg_level", "uint8", msg_level)
        self.msg_buff.append("msg_len", "uint16", len(msg_string))
        self.msg_buff.append("msg_string", "string", msg_string)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_CUSTOM_INFO_PUSH
        duss_result = self.event_client.send_msg(self.msg_buff)
        return duss_result

    def sub_info(self, info_id, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_MOBILE_INFO_PUSH
        )
        self.event_client.async_req_register(cmd_set_id, callback)
        self.msg_buff.init()
        self.msg_buff.append("info_id", "uint16", info_id)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SUB_MOBILE_INFO
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result != duml_cmdset.DUSS_MB_RET_OK:
            logger.error("MOBILE: error in sub_info(), ret code = " + str(duss_result))
            self.event_client.async_req_unregister(cmd_set_id)
        return duss_result

    def unsub_all_info(self):
        self.msg_buff.init()
        self.msg_buff.append("info_id", "uint16", 0x00)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SUB_MOBILE_INFO
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result == duml_cmdset.DUSS_MB_RET_OK:
            cmd_set_id = (
                duml_cmdset.DUSS_MB_CMDSET_RM << 8
                | duml_cmdset.DUSS_MB_CMD_RM_MOBILE_INFO_PUSH
            )
            self.event_client.async_req_unregister(cmd_set_id)
        else:
            logger.error(
                "MOBILE: error in unsub_info(), ret code = " + str(duss_result)
            )
        return duss_result


class ModulesStatus(object):
    def __init__(self, event_client):
        self.event_client = event_client

    def event_msg_invalid_check_callback_register(self, callback):
        self.event_client.event_msg_invalid_check_callback_register(callback)

    def sub_module_status_info(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_MODULE_STATUS_PUSH
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def unsub_module_status_info(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_MODULE_STATUS_PUSH
        )
        self.event_client.async_req_unregister(cmd_set_id)


class Tank(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.hdvt_uav_id)
        self.msg_buff.set_default_moduleid(rm_define.system_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def set_work_mode(self, mode):
        self.msg_buff.init()
        self.msg_buff.append("mode", "uint8", mode)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SET_TANK_WORK_MODE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def get_work_mode(self, mode):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GET_TANK_WORK_MODE
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_sdk_mode(self, enable):
        self.msg_buff.init()
        self.msg_buff.append("enable", "uint8", enable)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SDK_MODE_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_sub_node(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VBUS_ADD_NODE
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_VIRTUAL_BUS
        node_id = (9 & 0x1F) | ((6 << 5) & 0xE0)
        self.msg_buff.append("node_id", "uint8", node_id)
        self.msg_buff.append("version", "uint32", 0x3000000)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def del_sub_node(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VBUS_NODE_RESET
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_VIRTUAL_BUS
        node_id = (9 & 0x1F) | ((6 << 5) & 0xE0)
        self.msg_buff.append("node_id", "uint8", node_id)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def add_gimbal_and_chassis_sub_msg(self, freq, uuid_list, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_VIRTUAL_BUS << 8
            | duml_cmdset.DUSS_MB_CMD_VBUS_DATA_ANALYSIS
        )
        self.event_client.async_req_register(cmd_set_id, callback)

        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VBUS_ADD_MSG
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_VIRTUAL_BUS
        node_id = (9 & 0x1F) | ((6 << 5) & 0xE0)
        self.msg_buff.append("node_id", "uint8", node_id)
        self.msg_buff.append("msg_id", "uint8", 0)
        self.msg_buff.append("sub_config", "uint8", 0)
        self.msg_buff.append("mode", "uint8", 0)
        self.msg_buff.append("uuid_num", "uint8", len(uuid_list))
        for index in range(len(uuid_list)):
            self.msg_buff.append("uuid_%d_l" % index, "uint32", uuid_list[index])
            self.msg_buff.append("uuid_%d_h" % index, "uint32", 0x20009)
        self.msg_buff.append("freq", "uint16", freq)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)

        if duss_result != rm_define.DUSS_SUCCESS or (
            resp["data"][0] != 0 and resp["data"][0] != 0x23
        ):
            self.event_client.async_req_unregister(callback)
        return duss_result, resp

    def del_gimbal_and_chassis_sub_msg(self, msg_id):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_VBUS_DEL_MSG
        self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_VIRTUAL_BUS
        node_id = (9 & 0x1F) | ((6 << 5) & 0xE0)
        self.msg_buff.append("mode", "uint8", 0)
        self.msg_buff.append("node_id", "uint8", node_id)
        self.msg_buff.append("msg_id", "uint8", msg_id)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class SysTime(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.scratch_sys_id)
        self.msg_buff.set_default_moduleid(rm_define.system_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def get_latest_sys_time(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SCRIPT_LOCAL_SUB_SERVICE
        self.msg_buff.append("type", "uint8", 2)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class SDKModule(object):
    SDK_FUNCTION = 1
    STREAM_FUNCTION = 2
    AUDIO_FUNCTION = 3

    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.camera_id)
        self.msg_buff.set_default_moduleid(rm_define.camera_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def sdk_on(self, mode):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.SDK_FUNCTION)
        self.msg_buff.append("data", "uint8", 1 | (mode << 4) & 0xF0)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def sdk_off(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.SDK_FUNCTION)
        self.msg_buff.append("data", "uint8", 0)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def stream_on(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.STREAM_FUNCTION)
        self.msg_buff.append("data", "uint8", 1)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def stream_off(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.STREAM_FUNCTION)
        self.msg_buff.append("data", "uint8", 0)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def audio_on(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.AUDIO_FUNCTION)
        self.msg_buff.append("data", "uint8", 1)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def audio_off(self):
        self.msg_buff.init()
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_STREAM_CTRL
        self.msg_buff.append("function", "uint8", SDKModule.AUDIO_FUNCTION)
        self.msg_buff.append("data", "uint8", 0)
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result


class SensorAdapter(object):
    sensor_adapter_list = [
        rm_define.sensor_adapter_id,
        rm_define.sensor_adapter1_id,
        rm_define.sensor_adapter2_id,
        rm_define.sensor_adapter3_id,
        rm_define.sensor_adapter4_id,
        rm_define.sensor_adapter5_id,
        rm_define.sensor_adapter6_id,
        rm_define.sensor_adapter7_id,
    ]
    # dict_attr = {'set_mask': 1, 'adc_accuracy': 2, 'push_freq': 0, 'io_event': 0}
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.sensor_adapter_id)
        self.msg_buff.set_default_moduleid(rm_define.sensor_adapter1_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)
        # duss_result, resp = self.set_sensor_adapter_param(1, 0, **self.dict_attr);
        # logger.error('set_sensor_adapter_param: ret is:%s'%resp)

    def pulse_event_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_PUSH_SENSOR_ADAPTER_IO_EVENT
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def pulse_event_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_PUSH_SENSOR_ADAPTER_IO_EVENT
        )
        self.event_client.async_req_unregister(cmd_set_id)

    def get_sensor_adapter_data(self, board_id, port_num):
        self.msg_buff.init()
        self.msg_buff.append("port_num", "uint8", port_num)
        self.msg_buff.receiver = self.sensor_adapter_list[board_id]
        self.msg_buff.module_id = self.sensor_adapter_list[board_id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GET_SENSOR_ADAPTER_DATA
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_sensor_adapter_param(self, board_id, port_num, **attr):
        self.msg_buff.init()
        self.msg_buff.append("port_num", "uint8", port_num)
        self.msg_buff.append("set_mask", "uint8", attr["set_mask"])
        self.msg_buff.append("adc_accuracy", "uint8", attr["adc_accuracy"])
        self.msg_buff.append("push_freq", "uint8", attr["push_freq"])
        self.msg_buff.append("io_event", "uint8", attr["io_event"])
        self.msg_buff.receiver = self.sensor_adapter_list[board_id]
        self.msg_buff.module_id = self.sensor_adapter_list[board_id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SET_SENSOR_ADAPTER_PARAM
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def get_sensor_adapter_param(self, board_id, port_num):
        self.msg_buff.init()
        self.msg_buff.append("port_num", "uint8", port_num)
        self.msg_buff.receiver = self.sensor_adapter_list[board_id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_GET_SENSOR_ADAPTER_PARAM
        self.msg_buff.module_id = self.sensor_adapter_list[board_id]
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class IrDistanceSensor(object):
    TOF_MESAURE_CTRL_ID = 0x5A
    TOF_DATA_PUSH_SUB_ID = 0x5D
    TOF_DATA_GET_ID = 0x61
    TOF_DATA_PUSH_INFO_ID = 0x14

    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.tof1_id)
        self.msg_buff.set_default_moduleid(rm_define.tof1_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_PERCEPTION)

        self.id_to_host_id = {
            0: rm_define.tof_id,
            1: rm_define.tof1_id,
            2: rm_define.tof2_id,
            3: rm_define.tof3_id,
            4: rm_define.tof4_id,
        }

    def measure_ctrl(self, id, ctrl):
        self.msg_buff.init()
        self.msg_buff.append("cmdid", "uint8", self.TOF_MESAURE_CTRL_ID)
        self.msg_buff.append("tofid", "uint8", 0x41)
        self.msg_buff.append("ctrl", "uint8", ctrl)
        self.msg_buff.receiver = self.id_to_host_id[id]
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_PER_TOF_DATA_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def sub_tof_data_info_push(self, id, callback):
        self.msg_buff.init()
        self.msg_buff.append("cmdid", "uint8", self.TOF_DATA_PUSH_SUB_ID)
        self.msg_buff.append("tofid", "uint8", 0x41)
        self.msg_buff.append("sub_type", "uint8", 1)
        self.msg_buff.receiver = self.id_to_host_id[id]
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_PER_TOF_DATA_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        if duss_result == 0 and resp and resp["data"][0] == 0:
            cmd_set_id = (
                duml_cmdset.DUSS_MB_CMDSET_PERCEPTION << 8
                | duml_cmdset.DUSS_MB_CMD_PER_TOF_DATA_PUSH
            )
            self.event_client.async_req_register(cmd_set_id, callback)
        return duss_result

    def unsub_tof_data_info_push(self, id):
        self.msg_buff.init()
        self.msg_buff.append("cmdid", "uint8", self.TOF_DATA_PUSH_SUB_ID)
        self.msg_buff.append("tofid", "uint8", 0x41)
        self.msg_buff.append("sub_type", "uint8", 0)
        self.msg_buff.receiver = self.id_to_host_id[id]
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_PER_TOF_DATA_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result


class RoboticGripper(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.robotic_gripper_id)
        self.msg_buff.set_default_moduleid(rm_define.robotic_gripper_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_ROBOTIC_ARM)

        self.id_to_host_id = {1: rm_define.robotic_gripper_id}

    def robotic_gripper_ctrl(self, id, action, power_value):
        self.msg_buff.init()
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.append("action", "uint8", action)
        self.msg_buff.append("power_value", "uint16", power_value)
        self.msg_buff.receiver = rm_define.chassis_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_GRIPPER_CTRL_SET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result

    def get_robotic_gripper_status(self, id):
        self.msg_buff.init()
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.receiver = rm_define.chassis_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_GRIPPER_STATUS_GET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class Servo(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.servo_id)
        self.msg_buff.set_default_moduleid(rm_define.servo_id)

        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_ROBOTIC_ARM)

        self.task_id = random.randint(duml_cmdset.TASK_ID_MIN, duml_cmdset.TASK_ID_MAX)

        self.id_to_host_id = {
            0: rm_define.servo_id,
            1: rm_define.servo1_id,
            2: rm_define.servo2_id,
            3: rm_define.servo3_id,
            4: rm_define.servo4_id,
        }

    def get_servo_angle(self, id):
        self.msg_buff.init()
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.receiver = rm_define.chassis_id
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_SERVO_ANGLE_GET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def set_servo_angle(self, id, angle, cmd_type=rm_define.NO_TASK):
        self.msg_buff.init()
        self.msg_buff.module_id = self.id_to_host_id[id]
        if cmd_type == rm_define.TASK:
            task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
            self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
            self.msg_buff.append("task_id", "uint8", self.task_id)
            self.msg_buff.append("task_ctrl", "uint8", task_ctrl)

        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.append("angle", "int32", angle)
        self.msg_buff.receiver = rm_define.chassis_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_SERVO_ANGLE_SET

        if cmd_type == rm_define.TASK:
            self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
            self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SERVO_ANGLE_TASK_SET

            event_task = {}
            event_task["task_id"] = self.task_id
            event_task["receiver"] = self.msg_buff.receiver
            event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
            event_task["cmd_id"] = duml_cmdset.DUSS_MB_CMD_RM_SERVO_ANGLE_TASK_PUSH

            duss_result, identify = self.event_client.send_task_async(
                self.msg_buff, event_task
            )
            return duss_result, identify
        else:
            duss_result, resp = self.event_client.send_sync(self.msg_buff)
            return duss_result, resp

    def set_servo_angle_task_stop(self):
        task_ctrl = duml_cmdset.TASK_CTRL_STOP
        self.msg_buff.init()
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.append("task_id", "uint8", self.task_id)
        self.msg_buff.append("task_ctrl", "uint8", task_ctrl)
        self.msg_buff.append("id", "int32", 0)
        self.msg_buff.append("angle", "int32", 0)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_SERVO_ANGLE_TASK_SET
        self.msg_buff.receiver = rm_define.chassis_id

        duss_result = self.event_client.send_task_stop(self.msg_buff, 3)
        return duss_result

    def set_servo_speed(self, id, speed):
        self.msg_buff.init()
        self.msg_buff.module_id = self.id_to_host_id[id]
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.append("speed", "int32", speed)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_SERVO_SPEED_SET
        self.msg_buff.receiver = rm_define.chassis_id
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class RoboticArm(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.chassis_id)
        self.msg_buff.set_default_moduleid(rm_define.robotic_arm_id)

        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_ROBOTIC_ARM)

        self.task_id = random.randint(duml_cmdset.TASK_ID_MIN, duml_cmdset.TASK_ID_MAX)

        self.id_to_host_id = {
            1: rm_define.robotic_arm_id,
        }

    def robotic_arm_move_ctrl(
        self, id, type, mask, x, y, z, cmd_type=rm_define.NO_TASK
    ):
        self.msg_buff.init()
        if cmd_type == rm_define.TASK:
            task_ctrl = duml_cmdset.TASK_FREQ_10Hz << 2 | duml_cmdset.TASK_CTRL_START
            self.task_id = (self.task_id + 1) % duml_cmdset.TASK_ID_MAX
            self.msg_buff.append("task_id", "uint8", self.task_id)
            self.msg_buff.append("task_ctrl", "uint8", task_ctrl)

        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.append("type", "uint8", type)
        self.msg_buff.append("mask", "uint8", mask)
        self.msg_buff.append("x", "int32", x)
        self.msg_buff.append("y", "int32", y)
        self.msg_buff.append("z", "int32", z)
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_ARM_MOVE_CTRL

        if cmd_type == rm_define.TASK:
            self.msg_buff.cmd_set = duml_cmdset.DUSS_MB_CMDSET_RM
            self.msg_buff.cmd_id = (
                duml_cmdset.DUSS_MB_CMD_RM_ROBOTIC_ARM_POSITION_TASK_SET
            )
            event_task = {}
            event_task["task_id"] = self.task_id
            event_task["receiver"] = self.msg_buff.receiver
            event_task["cmd_set"] = duml_cmdset.DUSS_MB_CMDSET_RM
            event_task[
                "cmd_id"
            ] = duml_cmdset.DUSS_MB_CMD_RM_ROBOTIC_ARM_POSITION_TASK_PUSH

            duss_result, identify = self.event_client.send_task_async(
                self.msg_buff, event_task
            )
            return duss_result, identify
        else:
            duss_result, resp = self.event_client.send_sync(self.msg_buff)
            return duss_result, resp

    def get_robotic_arm_pos(self, id):
        self.msg_buff.init()
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_ARM_POSITION_GET
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def robotic_arm_stop(self, id):
        self.msg_buff.init()
        self.msg_buff.append(
            "id", "uint8", duss_event_msg.hostid2packid(self.id_to_host_id[id])[0]
        )
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_ROBOTIC_ARM_MOVE_STOP
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp


class Serial(object):
    def __init__(self, event_client):
        self.event_client = event_client
        self.msg_buff = duss_event_msg.EventMsg(
            tools.hostid2senderid(event_client.my_host_id)
        )
        self.msg_buff.set_default_receiver(rm_define.chassis_id)
        self.msg_buff.set_default_cmdset(duml_cmdset.DUSS_MB_CMDSET_RM)

    def set_serial_param(
        self,
        baud_rate,
        data_bit,
        odd_even,
        stop_bit,
        rx_enable,
        tx_enable,
        rx_buffer,
        tx_buffer,
    ):
        self.baud_rate_list = [9600, 19200, 38400, 57600, 115200]
        self.data_bit_list = ["cs7", "cs8", "cs9", "cs10"]
        self.odd_even_list = ["none", "odd", "even"]
        self.stop_bit_list = [1, 2]
        baud_rate_value = self.baud_rate_list.index(baud_rate)
        data_bit_value = self.data_bit_list.index(data_bit)
        odd_even_value = self.odd_even_list.index(odd_even)
        stop_bit_value = self.stop_bit_list.index(stop_bit)
        uart_config = (
            ((stop_bit_value & 0x01) << 7)
            | ((odd_even_value & 0x03) << 5)
            | ((data_bit_value & 0x03) << 3)
            | (baud_rate_value & 0x07)
        )
        uart_enable = ((tx_enable & 0x01) << 1) | (rx_enable & 0x01)
        logger.info("uart_config=%X, uart_enable=%X" % (uart_config, uart_enable))
        self.msg_buff.init()
        self.msg_buff.append("uart_config", "uint8", uart_config)
        self.msg_buff.append("uart_enable", "uint8", uart_enable)
        self.msg_buff.append("rx_buffer_size", "uint16", rx_buffer)
        self.msg_buff.append("tx_buffer_size", "uint16", tx_buffer)
        self.msg_buff.receiver = rm_define.chassis_id
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_UART_CONFIG
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def send_msg(self, len, buffer):
        self.msg_buff.init()
        self.msg_buff.append("msg_type", "uint8", 0x02)
        self.msg_buff.append("data_len", "uint16", len)
        self.msg_buff.append("data", "string", buffer)
        self.msg_buff.receiver = rm_define.chassis_id
        logger.info("send_msg: len=%d, msg=%s" % (len, buffer))
        self.msg_buff.cmd_id = duml_cmdset.DUSS_MB_CMD_RM_UART_MSG
        duss_result, resp = self.event_client.send_sync(self.msg_buff)
        return duss_result, resp

    def recv_msg_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_UART_MSG
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def recv_msg_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8 | duml_cmdset.DUSS_MB_CMD_RM_UART_MSG
        )
        self.event_client.async_req_unregister(cmd_set_id)

    def status_msg_register(self, callback):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_UART_STATUS_PUSH
        )
        self.event_client.async_req_register(cmd_set_id, callback)

    def status_msg_unregister(self):
        cmd_set_id = (
            duml_cmdset.DUSS_MB_CMDSET_RM << 8
            | duml_cmdset.DUSS_MB_CMD_RM_UART_STATUS_PUSH
        )
        self.event_client.async_req_unregister(cmd_set_id)
