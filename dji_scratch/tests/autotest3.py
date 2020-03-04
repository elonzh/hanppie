import unittest

import scratch_unittest
import event_client
import rm_ctrl
import rm_define
import tools

test_client = scratch_unittest.TestResult()

class LedTest(unittest.TestCase):
    TOLERATE_ANGLE = 10
    event = event_client.EventClient()
    led_ctrl = rm_ctrl.LedCtrl(event)
    led_ctrl.init()

    def test_set_led(self):
        comps = [rm_define.armor_bottom_back,
                rm_define.armor_bottom_front,
                rm_define.armor_bottom_left,
                rm_define.armor_bottom_right,
                rm_define.armor_top_left,
                rm_define.armor_top_right,
                rm_define.armor_top_all,
                rm_define.armor_bottom_all,
                rm_define.armor_all]

        effects = [rm_define.effect_always_on,
                   rm_define.effect_always_off,
                   rm_define.effect_breath,
                   rm_define.effect_flash,
                   rm_define.effect_marquee]
        for effect in effects:
            for comp in comps:
                for r in range(0, 100, 40):
                    for g in range(0, 100, 40):
                        for b in range(0, 100, 40):
                            _, _, _, result = LedTest.led_ctrl.set_led(comp, r, g, b, effect)
                            self.assertEqual(result, rm_define.DUSS_SUCCESS)
                            _, _, _, result = LedTest.led_ctrl.set_top_led(comp, r, g, b, effect)
                            self.assertEqual(result, rm_define.DUSS_SUCCESS)
                            _, _, _, result = LedTest.led_ctrl.set_bottom_led(comp, r, g, b, effect)
                            self.assertEqual(result, rm_define.DUSS_SUCCESS)
                            tools.wait(10)

    def test_set_flash(self):
        comps = [rm_define.armor_bottom_back,
                rm_define.armor_bottom_front,
                rm_define.armor_bottom_left,
                rm_define.armor_bottom_right,
                rm_define.armor_top_left,
                rm_define.armor_top_right,
                rm_define.armor_top_all,
                rm_define.armor_bottom_all,
                rm_define.armor_all]

        for comp in comps:
            for freq in range(1, 500, 50):
                _, _, _, result = LedTest.led_ctrl.set_flash(comp, freq)
                self.assertEqual(result, rm_define.DUSS_SUCCESS)
                tools.wait(10)

    def test_set_single_led(self):
        comps = [rm_define.armor_top_left,
                rm_define.armor_top_right,
                rm_define.armor_top_all,
                ]
        effects = [rm_define.effect_always_on,
                   rm_define.effect_always_off]

        for effect in effects:
            for comp in comps:
                for idx in range(1,9):
                    _, _, _, result = LedTest.led_ctrl.set_single_led(comp, idx, effect)
                    self.assertEqual(result, rm_define.DUSS_SUCCESS)
                    _, _, _, result = LedTest.led_ctrl.turn_off(comp)
                    self.assertEqual(result, rm_define.DUSS_SUCCESS)

try:
    suite = unittest.TestSuite()
    test_cases = [LedTest("test_set_led"),
                  LedTest("test_set_flash"),
                  LedTest("test_set_single_led")]
    suite.addTests(test_cases)
    runner = unittest.TextTestRunner(verbosity = 2)
    test_result = runner.run(suite)
except:
    pass
# test stopping
LedTest.led_ctrl.stop()
LedTest.event.stop()

# set test results
test_client.set_test_result(test_result.wasSuccessful())
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)
