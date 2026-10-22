package cn.elonzh.hanppie.robot.session

enum class RequestProblem { TIMED_OUT, REJECTED, INVALID_RESPONSE, DISCONNECTED, NOT_APPLIED }

class RobotRequestException(val problem: RequestProblem, val responseCode: Int? = null) :
    Exception("Robot request: $problem${responseCode?.let { " ($it)" }.orEmpty()}")
