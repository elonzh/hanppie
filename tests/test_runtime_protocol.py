from __future__ import annotations

from hanppie.runtime import duml_crc
from hanppie.runtime.duss_event_msg import EventMsg, unpack


def test_crc8_known_vector() -> None:
    assert duml_crc.duss_util_crc8_calc([0x55, 0x0D, 0x04], 0x77) == 0x33


def test_event_message_round_trip() -> None:
    event = EventMsg(sender=905)
    event.receiver = 903
    event.seq_num = 42
    event.cmd_type = 0x40
    event.cmd_set = 0x3F
    event.cmd_id = 0x4C
    event.append("enabled", "uint8", 1)

    message = unpack(event.pack())

    assert message is not None
    assert message["sender"] == 905
    assert message["receiver"] == 903
    assert message["seq_num"] == 42
    assert message["cmd_set"] == 0x3F
    assert message["cmd_id"] == 0x4C
    assert message["data"] == [1]
