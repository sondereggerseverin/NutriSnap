package ch.nutrisnap.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.supabase.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    // null = noch am Laden (Session-Restore), false = nicht eingeloggt, true = eingeloggt
    private val _isLoggedIn = MutableStateFlow<Boolean?>(null)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn

    init {
        viewModelScope.launch {
            // LOGIN TEMPORARILY DISABLED — set to true to re-enable Supabase auth:
            // AuthRepository.awaitSession()
            // _isLoggedIn.value = AuthRepository.isLoggedIn
            _isLoggedIn.value = true
        }
    }

    fun onLoggedIn() { _isLoggedIn.value = true }
    fun onLoggedOut() {
        viewModelScope.launch {
            AuthRepository.signOut()
            _isLoggedIn.value = false
        }
    }
}
