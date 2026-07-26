package com.tingyiting.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.repository.WebDavRepository
import com.tingyiting.data.store.WebDavConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val webDavRepository: WebDavRepository
) : ViewModel() {

    val config: StateFlow<WebDavConfig?> = webDavRepository.configFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            webDavRepository.getConfig()
        )

    fun clearWebDav() {
        viewModelScope.launch { webDavRepository.clearConfig() }
    }
}
