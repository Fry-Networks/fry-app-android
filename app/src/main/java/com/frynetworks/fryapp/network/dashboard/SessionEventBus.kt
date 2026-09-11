package com.frynetworks.fryapp.network.dashboard

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Session-level events raised by the network layer (fingerprint mismatch, expiry) for the UI. */
@Singleton
class SessionEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<SessionEvent> = _events

    fun emit(event: SessionEvent) {
        _events.tryEmit(event)
    }
}
