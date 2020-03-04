import unittest

import scratch_unittest
import event_client
import rm_ctrl
import rm_define
import tools

test_client = scratch_unittest.TestResult()

class MediaTest(unittest.TestCase):
    event = event_client.EventClient()
    media_ctrl = rm_ctrl.MediaCtrl(event)

    def test_capture(self):
        _, _, _, result = MediaTest.media_ctrl.capture()
        self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_sound_recoginzation(self):
        _, _, _, result = MediaTest.media_ctrl.enable_sound_recognition(rm_define.sound_detection_applause)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        _, _, _, result = MediaTest.media_ctrl.disable_sound_recognition(rm_define.sound_detection_applause)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_record(self):
        _, _, _, result = MediaTest.media_ctrl.record(1)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)
        tools.wait(1000)
        _, _, _, result = MediaTest.media_ctrl.record(0)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_play_sound(self):
        _, _, _, result = MediaTest.media_ctrl.play_sound(rm_define.media_music_theme_dji)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)

    def test_play_lib_sound(self):
        _, _, _, result = MediaTest.media_ctrl.play_lib_sound(0)
        self.assertEqual(result, rm_define.DUSS_SUCCESS)

try:
    suite = unittest.TestSuite()
    test_cases = [#MediaTest("test_capture"),
                  MediaTest("test_sound_recoginzation"),
                  #MediaTest("test_record"),
                  MediaTest("test_play_sound"),
                  MediaTest("test_play_lib_sound")]
    suite.addTests(test_cases)
    runner = unittest.TextTestRunner(verbosity = 2)
    test_result = runner.run(suite)
except:
    pass
# test stopping
MediaTest.media_ctrl.stop()
MediaTest.event.stop()

# set test results
test_client.set_test_result(test_result.wasSuccessful())
test_client.set_test_finished()

while not test_client.get_test_exit():
    tools.wait(100)
