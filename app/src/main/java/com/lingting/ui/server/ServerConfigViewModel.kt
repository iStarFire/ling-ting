package com.lingting.ui.server

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lingting.data.repository.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerConfigUiState(
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isTesting: Boolean = false,
    val testResult: String? = null,
    val isEditing: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    application: Application,
    private val webDavRepository: WebDavRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState = _uiState.asStateFlow()

    init {
        // 编辑模式：预填已有配置（密码留空，提交时若为空则保留原密码）
        val existing = webDavRepository.getConfig()
        if (existing != null) {
            _uiState.value = _uiState.value.copy(
                baseUrl = existing.baseUrl,
                username = existing.username,
                isEditing = true
            )
        }
    }

    fun updateBaseUrl(url: String) {
        _uiState.value = _uiState.value.copy(baseUrl = url, testResult = null)
    }

    fun updateUsername(username: String) {
        _uiState.value = _uiState.value.copy(username = username, testResult = null)
    }

    fun updatePassword(password: String) {
        _uiState.value = _uiState.value.copy(password = password, testResult = null)
    }

    fun testConnection() {
        val state = _uiState.value
        val baseUrl = state.baseUrl.trimEnd('/')
        if (baseUrl.isBlank()) {
            _uiState.value = state.copy(testResult = "请输入服务器地址")
            return
        }

        _uiState.value = state.copy(isTesting = true, testResult = null)

        viewModelScope.launch {
            // 密码留空且已存在配置时，复用已保存密码
            val existing = webDavRepository.getConfig()
            val effectivePassword =
                if (state.password.isBlank() && existing != null) existing.password else state.password

            val result = webDavRepository.testConnection(baseUrl, state.username, effectivePassword)
            result.fold(
                onSuccess = {
                    webDavRepository.persist(baseUrl, state.username, effectivePassword)
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "✅ 连接成功，已保存",
                        isSaved = true
                    )
                },
                onFailure = { e ->
                    Log.e("ServerConfig", "WebDAV 连接失败 baseUrl=$baseUrl user=${state.username}", e)
                    val reason = buildString {
                        append(e.javaClass.simpleName)
                        if (!e.message.isNullOrBlank()) append(": ${e.message}")
                        e.cause?.let { c ->
                            append(" (cause: ${c.javaClass.simpleName}: ${c.message ?: "无信息"})")
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "❌ 连接失败: $reason"
                    )
                }
            )
        }
    }
}
