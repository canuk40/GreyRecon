package com.greyrecon.app.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DeviceHistoryStore(application)

    val history: StateFlow<List<DeviceRecord>> = store.history.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val events: StateFlow<List<NetworkEvent>> = store.events.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    fun setCustomName(id: String, name: String?) = viewModelScope.launch { store.setCustomName(id, name) }
    fun setNotes(id: String, notes: String?) = viewModelScope.launch { store.setNotes(id, notes) }
}
