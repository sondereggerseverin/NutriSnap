package ch.nutrisnap.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.supabase.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    private val _isLoggedIn = MutableStateFlow(AuthRepository.isLoggedIn)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn

    fun onLoggedIn() { _isLoggedIn.value = true }
    fun onLoggedOut() {
        viewModelScope.launch {
            AuthRepository.signOut()
            _isLoggedIn.value = false
        }
    }
}
