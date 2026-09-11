package com.frynetworks.fryapp.ui.common

import androidx.lifecycle.ViewModel
import com.frynetworks.fryapp.auth.SessionRepository
import com.frynetworks.fryapp.auth.SessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Read-only view of the dashboard session for screens that only need "is a wallet signed in". */
@HiltViewModel
class SessionViewModel @Inject constructor(session: SessionRepository) : ViewModel() {
    val state: StateFlow<SessionState> = session.state
}
