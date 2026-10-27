// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

/**
 * Read-only, device-specific firmware facts collected over ADB.
 *
 * A successful property read does NOT demonstrate that write commands are safe.
 * Unknown capability remains unverified, even if Android versions match.
 * Serial numbers, Android ID, MAC and ADB keys are deliberately excluded.
 */
data class DeviceCapabilities(
    val manufacturer: String?,
    val model: String?,
    val androidVersion: String?,
    val sdk: Int?,
    val buildId: String?,
    val firmwareFingerprint: String?,
    val detectedAt: Long,
) {
    val exactFirmwareKnown: Boolean
        get() = !model.isNullOrBlank() && !firmwareFingerprint.isNullOrBlank()

    /** A displayed firmware identity is not a compatibility certification. */
    val verifiedCompatibility: Boolean get() = false
}

/** Normalizes untrusted getprop output without inventing missing metadata. */
object CapabilityProperties {
    fun value(raw: String?): String? = raw
        ?.lineSequence()
        ?.firstOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "unknown" && it != "null" }
        ?.take(250)
        ?.filter { it.code >= 32 && it.code != 127 }

    fun sdk(raw: String?): Int? = value(raw)?.toIntOrNull()?.takeIf { it in 1..100 }
}
