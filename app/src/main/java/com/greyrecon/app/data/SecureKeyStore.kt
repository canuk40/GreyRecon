package com.greyrecon.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.greyrecon.app.ai.AIProviderType
import java.security.SecureRandom

/**
 * Persists the user's own API keys (BYOK) via Android's Keystore-backed
 * EncryptedSharedPreferences instead of the plain-text `remember { }` session
 * cache the per-device action UI shipped with first -- keys now survive an
 * app restart instead of needing re-entry every session.
 */
class SecureKeyStore(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "greyrecon_secure_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var deepseekKey: String?
        get() = prefs.getString(KEY_DEEPSEEK, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_DEEPSEEK, value).apply()

    var anthropicKey: String?
        get() = prefs.getString(KEY_ANTHROPIC, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_ANTHROPIC, value).apply()

    var shodanKey: String?
        get() = prefs.getString(KEY_SHODAN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_SHODAN, value).apply()

    /** Free NVD API key -- optional (NVD works without one, just at a much lower rate limit). */
    var nvdKey: String?
        get() = prefs.getString(KEY_NVD, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_NVD, value).apply()

    /** Which provider "Ask AI" uses. Defaults to DeepSeek since that's the only one verified live so far. */
    var aiProvider: AIProviderType
        get() = prefs.getString(KEY_AI_PROVIDER, null)
            ?.let { runCatching { AIProviderType.valueOf(it) }.getOrNull() }
            ?: AIProviderType.DEEPSEEK
        set(value) = prefs.edit().putString(KEY_AI_PROVIDER, value.name).apply()

    /** Bearer token the MCP server requires on every request. Generated once, reused across restarts. */
    var mcpAuthToken: String?
        get() = prefs.getString(KEY_MCP_TOKEN, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_MCP_TOKEN, value).apply()

    /** Whether the user has turned the MCP server on -- used to restart it after a process death. */
    var mcpEnabled: Boolean
        get() = prefs.getBoolean(KEY_MCP_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_MCP_ENABLED, value).apply()

    companion object {
        private const val KEY_DEEPSEEK = "deepseek_api_key"
        private const val KEY_ANTHROPIC = "anthropic_api_key"
        private const val KEY_SHODAN = "shodan_api_key"
        private const val KEY_NVD = "nvd_api_key"
        private const val KEY_AI_PROVIDER = "ai_provider"
        private const val KEY_MCP_TOKEN = "mcp_auth_token"
        private const val KEY_MCP_ENABLED = "mcp_enabled"

        fun generateToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
