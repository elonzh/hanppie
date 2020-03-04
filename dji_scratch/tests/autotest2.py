import unittest

import scratch_unittest
import event_client
import rm_ctrl
import rm_define
import tools

test_client = scratch_unittest.TestResult()

class GimbalTest(unittest.TestCase):
    TOLERATE_ANGLE = 5
    event = event_client.EventClient()
    gimbal_ctrl = rm_ctrl.GimbalCtrl(event)
    gimbal_ctrl.init()
    def SetUp(self):
        self.test_return_middle()

    def tearDown(self):
        self.test_return_middle()

    def test_set_work_mode(self):
        modes = [rm_define.gimbal_free_mode,
                 rm_define.gimbal_yaw_follow_mode]
        for mode in modes:
            _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(mode)
            self.assertEqual(result, rm_define.DUSS_SUCCESS)
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)

    def test_return_middle(self):
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        _, _, _, result = GimbalTest.gimbal_ctrl.return_middle()
        self.assertIn(result, [rm_define.DUSS_SUCCESS, rm_define.DUSS_TASK_FINISHED])
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle - 0) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

    def test_rotate(self):
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
        GimbalTest.gimbal_ctrl.set_rotate_speed(30)
        GimbalTest.gimbal_ctrl.rotate(rm_define.gimbal_right)
        tools.wait(3000)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle - 90) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

    def test_rotate_with_degree(self):
        GimbalTest.gimbal_ctrl.set_rotate_speed(60)
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
        _, _, _, result = GimbalTest.gimbal_ctrl.rotate_with_degree(rm_define.gimbal_left, 150)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle + 150) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.rotate_with_degree(rm_define.gimbal_right, 150)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.rotate_with_degree(rm_define.gimbal_up, 20)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle - 20) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.rotate_with_degree(rm_define.gimbal_down, 40)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle + 20) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

    def test_yaw_pitch_ctrl(self):
        GimbalTest.gimbal_ctrl.set_rotate_speed(60)
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
        _, _, _, result = GimbalTest.gimbal_ctrl.yaw_ctrl(150)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle - 150) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.yaw_ctrl(-150)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle + 150) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.pitch_ctrl(20)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle - 20) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.pitch_ctrl(-20)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle + 20) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

    def test_angle_ctrl(self):
        GimbalTest.gimbal_ctrl.set_rotate_speed(60)
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_free_mode)
        _, _, _, result = GimbalTest.gimbal_ctrl.angle_ctrl(15, 90)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle - 90) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle - 15) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

        _, _, _, result = GimbalTest.gimbal_ctrl.angle_ctrl(-15, -90)
        yaw_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_yaw)
        result = abs(yaw_angle + 90) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)
        pitch_angle = GimbalTest.gimbal_ctrl.get_degree(rm_define.gimbal_axis_pitch)
        result = abs(pitch_angle + 15) < GimbalTest.TOLERATE_ANGLE
        self.assertEqual(result, True)

    def test_set_chassis_offset(self):
        _, _, _, result = GimbalTest.gimbal_ctrl.set_work_mode(rm_define.gimbal_yaw_follow_mode)
        _, _, _, result = GimbalTest.gimbal_ctrl.set_follow_chassis_offset(30)

try:
    suite = unittest.TestSuite()
    test_cases = [GimbalTest("test_set_work_mode"),
                  GimbalTest("test_rotate"),
                  GimbalTest("test_rotate_with_degree"),
                  GimbalTest("test_yaw_pitch_ctrl"),
                  GimbalTest("test_angle_ctrl"),
                  GimbalTest("test_set_chassis_offset")]
    suite.addTests(test_cases)
    runner = unittest.TextTestRunner(verbosity = 2)
    test_result = runner.run(suite)
except:
    pass
# test stopping
GimbalTest.gimbal_ctrl.stop()
GimbalTest.event.stop()

# set test results
test_client.set_test_result(test_result.wasSuccessful())
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)
