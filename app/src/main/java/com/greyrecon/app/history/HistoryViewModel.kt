package com.greyrecon.app.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.greyrecon.app.engine.discovery.NetworkIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * History is per-network (see [NetworkIdentity]). The screen defaults to whichever network the
 * phone is on right now, and falls back to the most recently seen profile when offline or on
 * mobile data -- otherwise opening History away from home would show an empty list with no
 * explanation of why.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val store = DeviceHistoryStore(application)

    private val _selectedNetworkKey = MutableStateFlow<String?>(null)
    val selectedNetworkKey: StateFlow<String?> = _selectedNetworkKey.asStateFlow()

    /** True when the selected network is the one the phone is actually connected to right now. */
    private val _onSelectedNetwork = MutableStateFlow(false)
    val onSelectedNetwork: StateFlow<Boolean> = _onSelectedNetwork.asStateFlow()

    val networks: StateFlow<List<NetworkProfile>> = store.profiles.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList()
    )

    val history: StateFlow<List<DeviceRecord>> = _selectedNetworkKey
        .flatMapLatest { key -> if (key == null) flowOf(emptyList()) else store.history(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val events: StateFlow<List<NetworkEvent>> = _selectedNetworkKey
        .flatMapLatest { key -> if (key == null) flowOf(emptyList()) else store.events(key) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // NetworkIdentity reads the kernel neighbour table and can shell out to `ip neigh`,
            // so keep it off the main thread even though it is normally fast.
            val current = withContext(Dispatchers.IO) { NetworkIdentity.resolve(getApplication()) }
            if (current != null) {
                store.registerNetwork(current)
                _selectedNetworkKey.value = current.key
                _onSelectedNetwork.value = true
            } else {
                // Not on WiFi: show the most recently seen network rather than an unexplained
                // empty list. profiles is ordered lastSeenAt DESC.
                _selectedNetworkKey.value = store.profiles.first().firstOrNull()?.networkKey
                _onSelectedNetwork.value = false
            }
        }
    }

    fun selectNetwork(key: String) {
        _selectedNetworkKey.value = key
        viewModelScope.launch {
            val current = withContext(Dispatchers.IO) { NetworkIdentity.resolve(getApplication()) }
            _onSelectedNetwork.value = current?.key == key
        }
    }

    fun setNetworkLabel(key: String, label: String) = viewModelScope.launch {
        if (label.isNotBlank()) store.setNetworkLabel(key, label.trim())
    }

    fun setCustomName(id: String, name: String?) = viewModelScope.launch { store.setCustomName(id, name) }
    fun setNotes(id: String, notes: String?) = viewModelScope.launch { store.setNotes(id, notes) }
}
