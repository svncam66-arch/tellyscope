// SPDX-License-Identifier: GPL-3.0-only

package app.tellyscope.data

import org.json.JSONObject
import java.net.URI
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Reject-by-default reader for owner-signed, *firmware-exact* action profiles.
 *
 * Crypto proves an approved publisher signed these exact bytes, not that a command
 * is safe. Independent evidence and actual Xiaomi tests are a separate release gate.
 * No app key is currently provisioned: callers MUST supply the owner's independently
 * validated, pinned public key. A missing key never promotes a community list.
 */
object SignedProfilePolicy {
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{2,100}")
    private val BUILD = Regex("[^\\r\\n\\u0000]{8,250}")
    private const val MAX_BYTES = 64 * 1024

    data class ProposedAction(
        val operation: String,
        val packageName: String,
        val recovery: String,
        val risk: String,
        val dependencies: List<String>,
    )

    data class ApprovedProfile(
        val id: String,
        val revision: Int,
        val manufacturer: String,
        val model: String,
        val buildFingerprint: String,
        val sdk: Int,
        val approvalId: String,
        val sourceHosts: Set<String>,
        val actions: List<ProposedAction>,
    )

    sealed interface Verdict {
        data class Accepted(val profile: ApprovedProfile) : Verdict
        data class Rejected(val reason: String) : Verdict
    }

    /**
     * The key is a pinned, base64-encoded X.509 SubjectPublicKeyInfo RSA-2048+
     * public key. RSA/SHA-256 is supported at Android 12's minimum API.
     * The detached signature is over the *exact* UTF-8 profile bytes.
     */
    fun verify(
        jsonBytes: ByteArray,
        signatureBytes: ByteArray,
        pinnedPublicKeyBase64: String?,
        detected: DeviceCapabilities,
        nowMillis: Long = System.currentTimeMillis(),
    ): Verdict {
        if (pinnedPublicKeyBase64.isNullOrBlank()) return Verdict.Rejected("Ключ владельца профилей не настроен.")
        if (jsonBytes.isEmpty() || jsonBytes.size > MAX_BYTES) return Verdict.Rejected("Размер профиля недопустим.")
        if (signatureBytes.size !in 256..1024) return Verdict.Rejected("Подпись отсутствует или имеет недопустимую длину.")
        val valid = try {
            val encoded = Base64.getDecoder().decode(pinnedPublicKeyBase64)
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(encoded))
            if ((publicKey as? java.security.interfaces.RSAPublicKey)?.modulus?.bitLength() ?: 0 < 2048) return Verdict.Rejected("Слишком короткий ключ подписи.")
            Signature.getInstance("SHA256withRSA").apply { initVerify(publicKey); update(jsonBytes) }.verify(signatureBytes)
        } catch (_: Exception) { false }
        if (!valid) return Verdict.Rejected("Цифровая подпись не подтверждена.")
        return try {
            val o = JSONObject(jsonBytes.toString(Charsets.UTF_8))
            if (o.optInt("schema", -1) != 1) return Verdict.Rejected("Неизвестная версия профиля.")
            val id = o.getString("id").also { require(ID.matches(it)) }
            val revision = o.getInt("revision").also { require(it > 0) }
            val manufacturer = o.getString("manufacturer").trim().also { require(it.isNotBlank()) }
            val model = o.getString("model").trim().also { require(it.isNotBlank()) }
            val fingerprint = o.getString("build_fingerprint").also { require(BUILD.matches(it)) }
            val sdk = o.getInt("sdk").also { require(it in 1..100) }
            if (!manufacturer.equals(detected.manufacturer, ignoreCase = true) ||
                model != detected.model ||
                fingerprint != detected.firmwareFingerprint ||
                sdk != detected.sdk) return Verdict.Rejected("Не совпадает точная модель, API или прошивка устройства.")
            val approval = o.getJSONObject("owner_approval")
            require(approval.getBoolean("physical_tested"))
            require(approval.getString("tested_fingerprint") == fingerprint)
            val approvedAt = approval.getLong("approved_at")
            require(approvedAt > 0 && approvedAt <= nowMillis)
            val approvalId = approval.getString("review_id").also { require(ID.matches(it)) }
            val urls = approval.getJSONArray("independent_sources")
            require(urls.length() in 2..8)
            val hosts = (0 until urls.length()).map {
                val u = URI(urls.getString(it))
                require(u.scheme.equals("https", ignoreCase = true) && u.host != null &&
                    u.userInfo == null && u.fragment == null)
                u.host.lowercase()
            }.toSet()
            require(hosts.size >= 2) // same provider under two URLs is not corroboration
            val array = o.getJSONArray("actions")
            require(array.length() in 1..60)
            val actions = (0 until array.length()).map {
                val a = array.getJSONObject(it)
                val op = a.getString("operation")
                require(op == "disable") // Never allow uninstall or arbitrary shell via a profile.
                val pkg = a.getString("package").also { require(PKG.matches(it)) }
                val recovery = a.getString("recovery")
                require(recovery == "enable" && a.getString("risk").length in 10..1000)
                val dependencies = a.optJSONArray("dependencies")
                val deps = if (dependencies == null) emptyList() else
                    (0 until dependencies.length()).map { n -> dependencies.getString(n).also { require(PKG.matches(it)) } }
                ProposedAction(op, pkg, recovery, a.getString("risk"), deps)
            }
            require(actions.distinctBy { it.packageName }.size == actions.size)
            Verdict.Accepted(ApprovedProfile(id, revision, manufacturer, model, fingerprint, sdk, approvalId, hosts, actions))
        } catch (_: Exception) {
            Verdict.Rejected("Подписанный профиль содержит неполные или недопустимые данные.")
        }
    }
}
