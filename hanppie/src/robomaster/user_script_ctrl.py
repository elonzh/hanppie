import rm_define


class UserScriptCtrl(object):

    def __init__(self):
        self.block_running_percent = 100
        self.block_running_percent = 100
        self.block_running_fail_reason_code = 0
        self.block_running_state = rm_define.BLOCK_RUN_SUCCESS
        self.script_has_stop = False
        self.script_exit_flag = False

    def set_block_running_percent(self, percent):
        self.block_running_percent = percent

    def get_block_running_percent(self):
        return self.block_running_percent

    def set_block_running_fail_reason_code(self, reason):
        self.block_running_fail_reason_code = reason

    def reset_block_running_fail_reason_code(self):
        self.block_running_fail_reason_code = 0

    def get_block_running_fail_reason_code(self):
        return self.block_running_fail_reason_code

    def set_block_running_state(self, result):
        if result == rm_define.DUSS_SUCCESS or result == rm_define.DUSS_TASK_FINISHED:
            self.block_running_state = rm_define.BLOCK_RUN_SUCCESS
        elif result == rm_define.DUSS_TASK_TIMEOUT:
            self.block_running_state = rm_define.BLOCK_ERR_TIMEOUT
        elif result == rm_define.DUSS_TASK_INTERRUPT:
            self.block_running_state = rm_define.BLOCK_ERR_TASK_INTERRUPT
        elif result == rm_define.DUSS_TASK_REJECTED:
            self.block_running_state = rm_define.BLOCK_ERR_TASK_REJECTED
        elif result == rm_define.DUSS_TASK_TIMEOUT:
            self.block_running_state = rm_define.BLOCK_ERR_TASK_TIMEOUT
        else:
            self.block_running_state = result

    def get_block_running_state(self):
        return self.block_running_state

    def set_script_has_stopped(self):
        self.script_has_stop = True

    def check_script_has_stopped(self):
        return self.script_has_stop

    def set_stop_flag(self):
        self.script_exit_flag = True

    def reset_stop_flag(self):
        self.script_exit_flag = False

    def check_stop(self):
        return self.script_exit_flag
