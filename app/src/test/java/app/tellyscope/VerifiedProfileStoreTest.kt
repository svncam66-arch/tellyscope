// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope

import app.tellyscope.data.DeviceCapabilities
import app.tellyscope.data.SignedProfilePolicy
import app.tellyscope.data.VerifiedProfileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VerifiedProfileStoreTest {
    @get:Rule val folders = TemporaryFolder()
    private val device = DeviceCapabilities("Xiaomi", "TestBox", "12", 32, "ABCD",
        "xiaomi/testbox/build:12/release-keys", 1L)

    private fun signed(revision: Int, key: java.security.KeyPair): Pair<ByteArray, ByteArray> {
        val manifest = """
            {"schema":1,"id":"sample-verified","revision":$revision,
             "manufacturer":"Xiaomi","model":"TestBox","sdk":32,
             "build_fingerprint":"xiaomi/testbox/build:12/release-keys",
             "owner_approval":{"physical_tested":true,"tested_fingerprint":"xiaomi/testbox/build:12/release-keys",
                "review_id":"QA-TEST-123","approved_at":100,
                "independent_sources":["https://github.com/project/tests","https://developer.android.com/platform/testing"]},
             "actions":[{"operation":"disable","package":"com.example.unneeded","recovery":"enable",
                "risk":"Manually verified noncritical test app.", "dependencies":[]}]}
        """.trimIndent().toByteArray()
        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(key.private)
            update(manifest)
        }.sign()
        return manifest to signature
    }

    @Test fun signatureAndExactBuildAreRecheckedWhenLoading() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pinned = Base64.getEncoder().encodeToString(key.public.encoded)
        val store = VerifiedProfileStore(folders.newFolder(), pinned)
        val (manifest, signature) = signed(1, key)
        assertTrue(store.import(manifest, signature, device) is SignedProfilePolicy.Verdict.Accepted)
        assertTrue(store.current(device) is SignedProfilePolicy.Verdict.Accepted)
        assertTrue(store.current(device.copy(firmwareFingerprint = "different build"))
            is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun previousVersionIsAvailableForExplicitRollbackOnly() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store = VerifiedProfileStore(folders.newFolder(), Base64.getEncoder().encodeToString(key.public.encoded))
        val old = signed(1, key)
        val fresh = signed(2, key)
        store.import(old.first, old.second, device)
        store.import(fresh.first, fresh.second, device)
        assertEquals(2, (store.current(device) as SignedProfilePolicy.Verdict.Accepted).profile.revision)
        val rollback = store.rollback(device)
        assertEquals(1, (rollback as SignedProfilePolicy.Verdict.Accepted).profile.revision)
        assertTrue(store.rollback(device) is SignedProfilePolicy.Verdict.Rejected)
    }

    @Test fun downgradeDoesNotReplaceActiveVersionWithoutRollback() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store = VerifiedProfileStore(folders.newFolder(), Base64.getEncoder().encodeToString(key.public.encoded))
        val newer = signed(3, key)
        val older = signed(1, key)
        store.import(newer.first, newer.second, device)
        assertTrue(store.import(older.first, older.second, device) is SignedProfilePolicy.Verdict.Rejected)
        assertEquals(3, (store.current(device) as SignedProfilePolicy.Verdict.Accepted).profile.revision)
    }

    @Test fun unconfiguredTrustRootCanNeverImport() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store = VerifiedProfileStore(folders.newFolder(), null)
        val example = signed(1, key)
        assertTrue(store.import(example.first, example.second, device) is SignedProfilePolicy.Verdict.Rejected)
        assertTrue(store.current(device) is SignedProfilePolicy.Verdict.Rejected)
    }
}
