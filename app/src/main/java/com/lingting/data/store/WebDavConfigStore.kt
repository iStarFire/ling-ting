package com.lingting.data.store

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** WebDAV 账号配置（持久化真相源） */
data class WebDavConfig(
    val baseUrl: String,
    val username: String,
    val password: String
)

interface WebDavConfigStore {
    fun save(config: WebDavConfig)
    fun load(): WebDavConfig?
    fun clear()
}

/**
 * 基于 Android Keystore + EncryptedSharedPreferences 的加密存储（security-crypto 1.0.0 API）。
 * 读取/写入失败（如密钥不可用、首装无数据）静默降级，不抛异常。
 */
@Singleton
class EncryptedWebDavConfigStore @Inject constructor(
    @ApplicationContext private val context: Context
) : WebDavConfigStore {

    private var masterKeyAlias: String? = null
    private var prefs: SharedPreferences? = null

    private fun getMasterKeyAlias(): String? {
        if (masterKeyAlias == null) {
            masterKeyAlias = try {
                MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            } catch (_: Exception) {
                null
            }
        }
        return masterKeyAlias
    }

    private fun getPrefs(): SharedPreferences? {
        val alias = getMasterKeyAlias() ?: return null
        if (prefs == null) {
            prefs = try {
                EncryptedSharedPreferences.create(
                    PREFS_FILE,
                    alias,
                    context,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (_: Exception) {
                null
            }
        }
        return prefs
    }

    override fun save(config: WebDavConfig) {
        getPrefs()?.edit()
            ?.putString(KEY_BASE_URL, config.baseUrl)
            ?.putString(KEY_USERNAME, config.username)
            ?.putString(KEY_PASSWORD, config.password)
            ?.apply()
    }

    override fun load(): WebDavConfig? {
        val p = getPrefs() ?: return null
        val baseUrl = p.getString(KEY_BASE_URL, null)
        if (baseUrl.isNullOrBlank()) return null
        return WebDavConfig(
            baseUrl = baseUrl,
            username = p.getString(KEY_USERNAME, "") ?: "",
            password = p.getString(KEY_PASSWORD, "") ?: ""
        )
    }

    override fun clear() {
        getPrefs()?.edit()?.clear()?.apply()
    }

    companion object {
        private const val PREFS_FILE = "webdav_config"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
    }
}
