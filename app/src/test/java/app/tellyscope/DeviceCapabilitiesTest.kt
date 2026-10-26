// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.CapabilityProperties
import app.tellyscope.data.DeviceCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCapabilitiesTest {
    @Test fun invalidOrEmptyPropertiesStayUnknown() {
        assertNull(CapabilityProperties.value(" "))
        assertNull(CapabilityProperties.value("unknown"))
        assertNull(CapabilityProperties.sdk("not-an-api"))
        assertNull(CapabilityProperties.sdk("0"))
        assertNull(CapabilityProperties.sdk("101"))
    }

    @Test fun propertyParsingIsReadOnlyAndDeterministic() {
        assertEquals("Xiaomi", CapabilityProperties.value(" Xiaomi\n"))
        assertEquals(33, CapabilityProperties.sdk(" 33\n"))
    }

    @Test fun modelAndFingerprintAreNotProofOfCompatibility() {
        val c = DeviceCapabilities("Xiaomi", "MIBOX", "12", 32,
            "build123", "xiaomi/mibox/release:12/abcd/release-keys", 1L)
        assertTrue(c.exactFirmwareKnown)
        assertFalse(c.verifiedCompatibility)
    }

    @Test fun missingFingerprintNeverGivesKnownExactFirmware() {
        val c = DeviceCapabilities("Xiaomi", "MIBOX", "12", 32, null, null, 1L)
        assertFalse(c.exactFirmwareKnown)
        assertFalse(c.verifiedCompatibility)
    }
}
