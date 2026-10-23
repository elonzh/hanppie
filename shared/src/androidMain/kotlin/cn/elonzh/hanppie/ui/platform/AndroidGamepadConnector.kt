package cn.elonzh.hanppie.ui.platform

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import cn.elonzh.hanppie.robot.gamepad.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Official Android AOA discovery identity, isolated from robot-model capability checks. */
internal class AndroidGamepadConnector(context: Context) : GamepadConnector {
    private val app = context.applicationContext
    private val manager = app.getSystemService(UsbManager::class.java)
    override val available get() = app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_ACCESSORY)
    private var discovered: Map<String, UsbAccessory> = emptyMap()

    @Synchronized
    override fun accessories(): List<GamepadAccessory> {
        val current = manager.accessoryList.orEmpty().filter { it.manufacturer == "DJI" && it.model == "GD0MA" }
        // UsbAccessory.equals calls permission-gated getSerial(). Discovery must not invoke it.
        discovered = current.associateBy { accessory -> discovered.entries.firstOrNull { sameDescriptor(it.value, accessory) }?.key ?: UUID.randomUUID().toString() }
        return discovered.keys.mapIndexed { index, id -> GamepadAccessory(id, "DJI Gamepad ${index + 1}") }
    }

    override suspend fun open(id: String): GamepadTransport {
        val accessory = synchronized(this) { discovered[id] } ?: error("Accessory no longer present")
        if (!manager.hasPermission(accessory)) permission(accessory)
        if (!manager.hasPermission(accessory) || manager.accessoryList.orEmpty().none { sameDescriptor(it, accessory) }) throw GamepadPermissionDenied()
        val descriptor = manager.openAccessory(accessory) ?: error("Accessory could not be opened")
        try { return AoaTransport(app, accessory, descriptor) } catch (error: Exception) { descriptor.close(); throw error }
    }

    private suspend fun permission(accessory: UsbAccessory) = suspendCancellableCoroutine<Unit> { continuation ->
        val action = "${app.packageName}.GAMEPAD_PERMISSION.${UUID.randomUUID()}"
        val finished = AtomicBoolean(false)
        val intent = PendingIntent.getBroadcast(app, 0, Intent(action).setPackage(app.packageName), PendingIntent.FLAG_IMMUTABLE)
        lateinit var receiver: BroadcastReceiver
        fun finish(allowed: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { app.unregisterReceiver(receiver) }; intent.cancel()
            if (allowed) continuation.resume(Unit) else continuation.resumeWithException(GamepadPermissionDenied())
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, result: Intent) {
                // The immutable, private token identifies this request. Grant extras are unnecessary:
                // UsbManager checks the exact original accessory's permission before it may be opened.
                if (result.action == action) finish(manager.hasPermission(accessory))
            }
        }
        try {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { if (finished.compareAndSet(false, true)) {
                runCatching { app.unregisterReceiver(receiver) }; intent.cancel()
            } }
            if (continuation.isActive) manager.requestPermission(accessory, intent)
        } catch (error: Exception) {
            if (finished.compareAndSet(false, true)) {
                runCatching { app.unregisterReceiver(receiver) }; intent.cancel()
                continuation.resumeWithException(error)
            }
        }
    }
}

/** Called on the shared session's IO scope; closing descriptors interrupts a blocked read. */
private fun sameDescriptor(first: UsbAccessory, second: UsbAccessory): Boolean =
    first.manufacturer == second.manufacturer && first.model == second.model && first.description == second.description &&
        first.version == second.version && first.uri == second.uri

private class AoaTransport(private val app: Context, accessory: UsbAccessory, descriptor: ParcelFileDescriptor) : GamepadTransport {
    private val closed = AtomicBoolean(false)
    private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    private val output = try { ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)) }
        catch (error: Exception) { input.close(); throw error }
    private val detach = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val received = runCatching { IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java) }.getOrNull()
            if (intent.action == UsbManager.ACTION_USB_ACCESSORY_DETACHED && received != null && sameDescriptor(received, accessory)) close()
        }
    }
    init {
        try { ContextCompat.registerReceiver(app, detach, IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED) }
        catch (error: Exception) { close(); throw error }
    }
    override suspend fun read(): ByteArray? {
        // Read a full USB transfer before handing fragments to shared framing (Android USB accessory guide).
        val buffer = ByteArray(16_384)
        val count = input.read(buffer)
        return if (count < 0) null else buffer.copyOf(count)
    }
    override suspend fun write(bytes: ByteArray) { check(!closed.get()); output.write(bytes); output.flush() }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { app.unregisterReceiver(detach) }
        runCatching { input.close() }; runCatching { output.close() }
    }
}
