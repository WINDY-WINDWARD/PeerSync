package com.peersync.app.ui.sessionlist

import com.peersync.app.model.ConnectionState
import com.peersync.app.model.DiscoveredSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionListViewModel {

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredSessions = MutableStateFlow<List<DiscoveredSession>>(emptyList())
    val discoveredSessions: StateFlow<List<DiscoveredSession>> = _discoveredSessions.asStateFlow()

    private val _showCreateDialog = MutableStateFlow(false)
    val showCreateDialog: StateFlow<Boolean> = _showCreateDialog.asStateFlow()

    private val _selectedSessionToJoin = MutableStateFlow<DiscoveredSession?>(null)
    val selectedSessionToJoin: StateFlow<DiscoveredSession?> = _selectedSessionToJoin.asStateFlow()

    fun updateSessions(sessions: List<DiscoveredSession>) {
        _discoveredSessions.value = sessions
    }

    fun updateConnectionState(state: ConnectionState) {
        _connectionState.value = state
    }

    fun openCreateDialog() {
        _showCreateDialog.value = true
    }

    fun closeCreateDialog() {
        _showCreateDialog.value = false
    }

    fun selectSessionForJoin(session: DiscoveredSession) {
        _selectedSessionToJoin.value = session
    }

    fun closeJoinDialog() {
        _selectedSessionToJoin.value = null
    }
}
