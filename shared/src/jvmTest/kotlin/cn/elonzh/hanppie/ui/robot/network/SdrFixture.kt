package cn.elonzh.hanppie.ui.robot.network

import cn.elonzh.hanppie.robot.network.*
import kotlinx.coroutines.*

internal class SdrFixture : RobotSdrService {
    var settings: SdrSettings? = SdrSettings(0, 0, 1011, 1, true)
    var reads = 0
    val writes = mutableListOf<SdrChange>()
    var readGate: CompletableDeferred<Unit>? = null
    var error: Exception? = null
    var readError: Exception? = null
    var applyWrites = true
    override suspend fun read(): SdrSettings? {
        reads++; withContext(NonCancellable) { readGate?.await() }; readError?.let { throw it }; return settings
    }
    override suspend fun set(change: SdrChange, onDispatch: () -> Unit) {
        onDispatch(); writes += change; error?.let { throw it }
        if (applyWrites) settings = settings?.let { before -> when (change) {
            is SdrChange.Point -> before.copy(point = change.value)
            is SdrChange.Band -> before.copy(bandCode = change.value)
            is SdrChange.Selection -> before.copy(selectionCode = change.value)
            is SdrChange.Bandwidth -> before.copy(bandwidthCode = change.value)
        } }
    }
}
