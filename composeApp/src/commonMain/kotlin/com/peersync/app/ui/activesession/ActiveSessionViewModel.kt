package com.peersync.app.ui.activesession

import com.peersync.app.model.PeerDevice
import com.peersync.app.model.SessionInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ActiveSessionViewModel {

    private val _sessionInfo = MutableStateFlow<SessionInfo?>(null)
    val sessionInfo: StateFlow<SessionInfo?> = _sessionInfo.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    fun updateSessionInfo(info: SessionInfo?) {
        _sessionInfo.value = info
    }

    fun setSpeaking(speaking: Boolean) {
        _isSpeaking.value = speaking
    }
}
