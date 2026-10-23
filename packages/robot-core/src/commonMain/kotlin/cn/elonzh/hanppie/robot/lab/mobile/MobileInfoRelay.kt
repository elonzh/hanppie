package cn.elonzh.hanppie.robot.lab.mobile

import cn.elonzh.hanppie.robot.protocol.DussFrame
import cn.elonzh.hanppie.robot.protocol.Protocol
import cn.elonzh.hanppie.robot.protocol.u16
import cn.elonzh.hanppie.robot.telemetry.Telemetry

/** Connection/run scoped subscription. Every method runs under AppSession's transmit lock. */
internal class MobileInfoRelay(
    private val source: MobileSensorSource,
    private val now: () -> Long,
    private val acknowledge: (DussFrame, Int) -> Unit,
    private val push: (ByteArray) -> Unit,
    private val log: (String) -> Unit,
) {
    private var owner: String? = null
    private var mask = 0
    private var nextRead = 0L
    private val lastData = mutableMapOf<MobileSensor, Long>()
    private val stamps = mutableMapOf<MobileSensor, Long>()
    private var closed = false

    fun reset(runId: String? = null) {
        stopSampling()
        owner = runId
    }

    fun close() { reset(); closed = true }

    private fun stopSampling() {
        mask = 0; stamps.clear(); lastData.clear(); nextRead = 0
        runCatching { source.stop() }.onFailure { log("终端传感器停止失败：${it.message}") }
    }

    fun receive(frame: DussFrame, allowed: Boolean) {
        if (closed || !frame.valid) return
        if (frame.receiver == Protocol.HOST_MOBILE && frame.attr == Protocol.ATTR_NO_ACK &&
            frame.sender == Protocol.HOST_HDVT_UAV && frame.set == Protocol.CMDSET_RM && frame.id == Protocol.CMD_RM_SCRIPT_BLOCK_STATUS_PUSH) {
            val status = Telemetry.labScriptStatus(frame) ?: return
            if (owner != null && status.guid.equals(owner, ignoreCase = true) && (status.isIdle || status.isFailed)) reset()
            return
        }
        if (frame.sender != Protocol.HOST_SCRATCH_SCRIPT || frame.receiver != Protocol.HOST_MOBILE ||
            frame.attr != Protocol.ATTR_NEED_ACK || frame.set != Protocol.CMDSET_RM || frame.id != Protocol.CMD_RM_SUB_MOBILE_INFO) return
        if (frame.payload.size != 2 || frame.payload.u16(0) and 0xfff0 != 0 || owner == null || !allowed) {
            acknowledge(frame, 1)
            return
        }
        val requested = frame.payload.u16(0) and 7
        val supported = requested and source.availableMask
        stopSampling()
        if (supported != 0) {
            try {
                source.start(supported); mask = supported
                MobileSensor.entries.filter { supported and it.bit != 0 }.forEach { lastData[it] = now() }
            }
            catch (error: Exception) {
                stopSampling(); acknowledge(frame, 1)
                log("终端传感器采样失败：${error.message}")
                return
            }
        }
        // The recovered script requests 0x0f at init; its GPS parser is empty. No location service.
        val unavailable = requested and source.availableMask.inv()
        acknowledge(frame, if (unavailable == 0 && (requested != 0 || frame.payload.u16(0) == 0)) 0 else 1)
        if (unavailable != 0) log("此终端不支持脚本订阅的部分或全部运动传感器；不会生成替代读数")
    }

    fun tick(allowed: Boolean) {
        if (!allowed) { if (mask != 0) stopSampling(); return }
        if (closed || owner == null || mask == 0 || now() < nextRead) return
        nextRead = now() + 100
        val readings = try { source.read() } catch (error: Exception) {
            stopSampling(); log("终端传感器读取失败：${error.message}"); return
        }
        val payload = mutableListOf<Byte>()
        readings.forEach { value ->
            if (mask and value.sensor.bit == 0 || value.ageMillis !in 0..250 || value.stamp < 0 ||
                stamps[value.sensor]?.let { value.stamp <= it } == true ||
                !value.x.isFinite() || !value.y.isFinite() || !value.z.isFinite()) return@forEach
            stamps[value.sensor] = value.stamp
            lastData[value.sensor] = now()
            payload += value.sensor.wireType.toByte(); payload += 12.toByte()
            listOf(value.x, value.y, value.z).forEach { number ->
                val bits = number.toBits()
                repeat(4) { payload += (bits ushr (it * 8)).toByte() }
            }
        }
        if (payload.isNotEmpty()) push(payload.toByteArray())
        val stalled = lastData.filterValues { now() - it >= 3_000 }.keys
        if (stalled.isNotEmpty()) {
            val names = stalled.joinToString()
            stopSampling(); log("终端运动传感器没有提供新读数（$names）；已停止采样，请重新运行脚本以订阅")
        }
    }
}
