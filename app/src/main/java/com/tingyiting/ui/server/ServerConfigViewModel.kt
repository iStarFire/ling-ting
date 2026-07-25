package com.tingyiting.ui.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.repository.WebDavRepository
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
    val isConfigured: Boolean = false
)

@HiltViewModel
class ServerConfigViewModel @Inject constructor(
    application: Application,
    private val webDavRepository: WebDavRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ServerConfigUiState())
    val uiState = _uiState.asStateFlow()

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
        if (state.baseUrl.isBlank()) {
            _uiState.value = state.copy(testResult = "请输入服务器地址")
            return
        }

        _uiState.value = state.copy(isTesting = true, testResult = null)

        viewModelScope.launch {
            webDavRepository.configure(
                baseUrl = state.baseUrl.trimEnd('/'),
                username = state.username,
                password = state.password
            )

            val result = webDavRepository.testConnection()
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "✅ 连接成功",
                        isConfigured = true
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isTesting = false,
                        testResult = "❌ 连接失败: ${e.message}",
                        isConfigured = false
                    )
                }
            )
        }
    }
}
