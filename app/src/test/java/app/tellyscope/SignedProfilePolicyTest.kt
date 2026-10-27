// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.DeviceCapabilities
import app.tellyscope.data.SignedProfilePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SignedProfilePolicyTest {
    private lateinit var pair: KeyPair
    private val device = DeviceCapabilities("Xiaomi", "MiBox-Sample", "12", 32,
        "2026.01", "xiaomi/sample/build:12/sample/release-keys", 1L)

    @Before fun keyPair() {
        pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    private val valid = """
      {"schema":1,"id":"xiaomi-verified-test","revision":1,
       "manufacturer":"Xiaomi","model":"MiBox-Sample","sdk":32,
       "build_fingerprint":"xiaomi/sample/build:12/sample/release-keys",
       "owner_approval":{"physical_tested":true,
          "tested_fingerprint":"xiaomi/sample/build:12/sample/release-keys",
          "review_id":"QA-2026-0001","approved_at":123456789,
          "independent_sources":["https://github.com/project/issues/10",
                                 "https://developer.android.com/platform/testing"]},
       "actions":[{"operation":"disable","package":"com.example.unneeded",
          "recovery":"enable","risk":"May disable a noncritical example application.",
          "dependencies":["com.example.runtime"]}]}
    """.trimIndent()

    private fun key() = Base64.getEncoder().encodeToString(pair.public.encoded)
    private fun signed(content: String = valid): Pair<ByteArray, ByteArray> {
        val data = content.toByteArray(Charsets.UTF_8)
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(data)
        }.sign()
        return data to sig
    }
    private fun verify(content: String = valid, target: DeviceCapabilities = device): SignedProfilePolicy.Verdict {
        val (json, signature) = signed(content)
        return SignedProfilePolicy.verify(json, signature, key(), target, 200_000_000L)
    }

    @Test fun acceptsOnlyOwnerSignedExactFirmware() {
        val verdict = verify()
        assertTrue(verdict is SignedProfilePolicy.Verdict.Accepted)
        val profile = (verdict as SignedProfilePolicy.Verdict.Accepted).profile
        assertEquals(1, profile.actions.size)
        assertEquals("disable", profile.actions.single().operation)
        assertEquals("enable", profile.actions.single().recovery)
        assertEquals(2, profile.sourceHosts.size)
    }

    @Test fun noOwnerKeyCannotEverPromoteCommunityList() {
        val (json, signature) = signed()
        assertTrue(SignedProfilePolicy.verify(json, signature, null, device) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun tamperingWithSignedProfileFails() {
        val (bytes, signature) = signed()
        val changed = String(bytes, Charsets.UTF_8).replace("com.example.unneeded", "com.example.something")
        assertTrue(SignedProfilePolicy.verify(changed.toByteArray(), signature, key(), device) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun wrongFirmwareOrWrongSdkIsNeverTrusted() {
        assertTrue(verify(target = device.copy(firmwareFingerprint = "another-build")) is SignedProfilePolicy.Verdict.Rejected)
        assertTrue(verify(target = device.copy(sdk = 33)) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun noPhysicalTestIsNeverApproved() {
        assertTrue(verify(valid.replace("\"physical_tested\":true", "\"physical_tested\":false"))
            is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun twoLinksFromSameHostDoNotQualifyAsIndependentEvidence() {
        val altered = valid.replace("https://developer.android.com/platform/testing", "https://github.com/another/issues/21")
        assertTrue(verify(altered) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun cannotIncludeArbitraryShellOrSystemUninstall() {
        val uninstall = valid.replace("\"operation\":\"disable\"", "\"operation\":\"uninstall_user0\"")
        assertTrue(verify(uninstall) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun cannotSneakShellPayloadIntoPackageName() {
        val injection = valid.replace("com.example.unneeded", "com.example.unneeded;rm -rf /")
        assertTrue(verify(injection) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun requiresSafeRecoveryInstruction() {
        val noRecovery = valid.replace("\"recovery\":\"enable\"", "\"recovery\":\"unknown\"")
        assertTrue(verify(noRecovery) is SignedProfilePolicy.Verdict.Rejected)
    }
}
