package com.lifelink.app.feature.updates

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.lifelink.app.data.repository.UpdatesRepository
import com.lifelink.app.domain.UpdateItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class UpdatesViewModel(private val repository: UpdatesRepository) : ViewModel() {
    val updates: StateFlow<List<UpdateItem>> = repository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun markRead(id: String) {
        viewModelScope.launch { repository.markRead(id) }
    }

    fun markAllRead() {
        viewModelScope.launch { repository.markAllRead() }
    }
}

class UpdatesViewModelFactory(private val repository: UpdatesRepository) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = UpdatesViewModel(repository) as T
}
