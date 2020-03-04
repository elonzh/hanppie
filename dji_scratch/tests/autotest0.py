import unittest

import scratch_unittest
import event_client
import rm_ctrl
import rm_define
import tools

test_client = scratch_unittest.TestResult()

class ChassisTest(unittest.TestCase):
    event = event_client.EventClient()
    chassis_ctrl = rm_ctrl.ChassisCtrl(event)
#    def SetUp(self):
#        self.event = event_client.EventClient()
#        self.chassis_ctrl = rm_ctrl.ChassisCtrl(self.event)

    def tearDown(self):
        ChassisTest.chassis_ctrl.set_wheel_speed(0, 0, 0, 0)

    def test_set_mode(self):
        modes = [rm_define.chassis_fpv_mode,
                 rm_define.chassis_sdk_free_mode,
                 rm_define.chassis_sdk_follow_mode]
        for mode in modes:
            _, _, _, result = ChassisTest.chassis_ctrl.set_mode(mode)
            self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_set_wheel_speed(self):
        _, _, _, result = ChassisTest.chassis_ctrl.set_mode(rm_define.chassis_sdk_free_mode)
        for i in range(-1000, 1000, 200):
            _, _, _, result = ChassisTest.chassis_ctrl.set_wheel_speed(i, i, i, i)
            self.assertEqual(result, rm_define.DUSS_SUCCESS)
            tools.wait(100)
        ChassisTest.chassis_ctrl.set_wheel_speed(0, 0, 0, 0)

    def test_chassis_move(self):
        dire = [rm_define.chassis_front,
                rm_define.chassis_back,
                rm_define.chassis_right,
                rm_define.chassis_left,
                rm_define.chassis_left_front,
                rm_define.chassis_right_front,
                rm_define.chassis_left_back,
                rm_define.chassis_right_back]

        modes = [rm_define.chassis_sdk_free_mode]
        for mode in modes:
            _, _, _, result = ChassisTest.chassis_ctrl.set_mode(mode)
            self.assertEqual(result, rm_define.DUSS_SUCCESS)
            for i in range(8):
                _, _, _, result = ChassisTest.chassis_ctrl.set_move_speed(i / 4)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.set_move_direction(dire[i])
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.set_rotate_speed(i * 5)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.move(i*5)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.move_with_time(dire[i], i/16)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.move_with_distance(dire[i], i / 16)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.rotate(rm_define.clockwise)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.rotate_with_time(rm_define.anticlockwise, i / 16)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.rotate_with_degree(rm_define.anticlockwise, i)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.move_and_rotate(i, dire[i])
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                #_, _, _, result = ChassisTest.chassis_ctrl.set_follow_gimbal_offset(i)
                #self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.move_with_degree(i, i / 16)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_chassis_pwm(self):
        comps = [rm_define.pwm0,
                 rm_define.pwm1,
                 rm_define.pwm2,
                 rm_define.pwm3,
                 rm_define.pwm4,
                 rm_define.pwm5,
                 rm_define.pwm_all]
        for comp in comps:
            for p in range(0, 1000, 100):
                _, _, _, result = ChassisTest.chassis_ctrl.set_pwm_value(comp, p)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = ChassisTest.chassis_ctrl.set_pwm_freq(comp, p)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)

try:
    suite = unittest.TestSuite()
    test_cases = [ChassisTest("test_set_mode"),
                  ChassisTest("test_set_wheel_speed"),
                  ChassisTest("test_chassis_move"),
                  ChassisTest("test_chassis_pwm")]
    suite.addTests(test_cases)
    runner = unittest.TextTestRunner(verbosity = 2)
    test_result = runner.run(suite)
except:
    pass
# test stopping
ChassisTest.chassis_ctrl.stop()
ChassisTest.chassis_ctrl.set_mode(rm_define.chassis_fpv_mode)
ChassisTest.event.stop()

# set test results
test_client.set_test_result(test_result.wasSuccessful())
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)