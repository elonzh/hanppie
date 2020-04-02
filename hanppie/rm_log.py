import logging

dji_scratch_logger = logging.getLogger("dji_scratch_logger")
dji_script_logger = logging.getLogger("dji_script_logger")

logger = logging.getLogger("dji_scratch_logger")


def dji_scratch_logger_get():
    global dji_scratch_logger
    dji_scratch_logger = logging.getLogger("dji_scratch_logger")
    return dji_scratch_logger


def dji_script_logger_get():
    global dji_script_logger
    dji_script_logger = logging.getLogger("dji_script_logger")
    return dji_script_logger
