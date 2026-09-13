package cn.elonzh.hanppie.robot.protocol

import kotlin.io.encoding.Base64

/**
 * Encodes the router provisioning payload understood by RoboMaster robots.
 *
 * The wire layout and byte transform are compatible with DJI's Apache-2.0 RoboMaster SDK
 * `STAConnInfo.pack` and `algo.simple_encrypt`; the QR renderer itself lives in the UI layer.
 */
object RouterProvisioning {
    const val MAX_SSID_BYTES = 32
    const val MIN_PASSWORD_BYTES = 8
    const val MAX_PASSWORD_BYTES = 31

    fun encode(ssid: String, password: String, appId: String, countryCode: String = "CN"): String {
        val ssidBytes = ssid.encodeToByteArray()
        val passwordBytes = password.encodeToByteArray()
        val appIdBytes = appId.encodeToByteArray()
        val countryBytes = countryCode.encodeToByteArray()
        require(ssidBytes.size in 1..MAX_SSID_BYTES) { "SSID must contain 1 to $MAX_SSID_BYTES UTF-8 bytes" }
        require(passwordBytes.size in MIN_PASSWORD_BYTES..MAX_PASSWORD_BYTES) {
            "Password must contain $MIN_PASSWORD_BYTES to $MAX_PASSWORD_BYTES UTF-8 bytes"
        }
        require(appIdBytes.size == 8 && appId.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) {
            "AppID must contain 8 hexadecimal ASCII characters"
        }
        require(countryBytes.size == 2 && countryCode.all { it in 'A'..'Z' }) {
            "Country code must contain 2 uppercase ASCII characters"
        }

        val header = (passwordBytes.size shl 6) or ssidBytes.size
        val plain = ByteArray(12 + ssidBytes.size + passwordBytes.size).apply {
            put16(0, header)
            appId.lowercase().encodeToByteArray().copyInto(this, 2)
            countryBytes.copyInto(this, 10)
            ssidBytes.copyInto(this, 12)
            passwordBytes.copyInto(this, 12 + ssidBytes.size)
        }
        var key = 0x07
        val encrypted = ByteArray(plain.size) { index ->
            (plain[index].toInt() xor key).toByte().also { key = ((key + 7) xor 178) and 255 }
        }
        return Base64.Default.encode(encrypted)
    }
}
