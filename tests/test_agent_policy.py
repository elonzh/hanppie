from __future__ import annotations

import pytest

from hanppie.agent.policy import GeneratedCodeRejected, RobotCodePolicy


@pytest.mark.parametrize(
    "code",
    [
        "import os\nresult = robot.status()",
        "result = open('/tmp/x').read(); robot.status()",
        "result = robot._runtime",
        "result = os.getcwd(); robot.status()",
        "def run():\n    return robot.status()\nresult = run()",
        "result = 1",
        "result = robot.status(",
    ],
)
def test_generated_code_policy_rejects_host_escape_and_invalid_code(code: str) -> None:
    with pytest.raises(GeneratedCodeRejected):
        RobotCodePolicy().validate(code)


def test_generated_code_policy_allows_composed_robot_program() -> None:
    code = """robot.arm()
try:
    for step in range(2):
        robot.chassis.drive_speed(z=15, lease_seconds=0.25)
        sleep(0.1)
        checkpoint("step", number=step)
finally:
    robot.stop()
    robot.disarm()
result = {"steps": 2, "status": robot.status()}
"""
    RobotCodePolicy().validate(code)
