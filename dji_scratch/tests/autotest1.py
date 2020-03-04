import unittest

import scratch_unittest
import event_client
import rm_ctrl
import rm_define
import tools

test_client = scratch_unittest.TestResult()

class GunTest(unittest.TestCase):
    event = event_client.EventClient()
    gun_ctrl = rm_ctrl.GunCtrl(event)
#    def SetUp(self):
#        self.event = event_client.EventClient()
#        self.chassis_ctrl = rm_ctrl.ChassisCtrl(self.event)

#    def tearDown(self):
#        self.chassis_ctrl.stop()
#        self.event.stop()

    def test_shoot(self):
        modes = [rm_define.single_fire_mode,
                 rm_define.multip_fire_mode]
        for mode in modes:
            _, _, _, result = GunTest.gun_ctrl.set_fire_mode(mode)
            self.assertEqual(result, rm_define.DUSS_SUCCESS)
            for count in range(2,6,2):
                _, _, _, result = GunTest.gun_ctrl.set_count(count)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = GunTest.gun_ctrl.fire_with_frequency(count)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = GunTest.gun_ctrl.fire()
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                _, _, _, result = GunTest.gun_ctrl.stop()
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                for ctrl in range(2):
                    _, _, _, result = GunTest.gun_ctrl.set_led(ctrl)
                    self.assertEqual(result, rm_define.DUSS_SUCCESS)
        _, _, _, result = GunTest.gun_ctrl.set_led(0)

try:
    suite = unittest.TestSuite()
    test_cases = [GunTest("test_shoot")]
    suite.addTests(test_cases)
    runner = unittest.TextTestRunner(verbosity = 2)
    test_result = runner.run(suite)
except:
    pass
# test stopping
GunTest.gun_ctrl.stop()
GunTest.event.stop()

# set test results
test_client.set_test_result(test_result.wasSuccessful())
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)
