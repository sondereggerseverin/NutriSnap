package ch.nutrisnap.app.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.supabase.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class AuthViewModel : ViewModel() {
    // ─── DEV FLAG ───────────────────────────────────────────────────────────────
    // AUTH_ENABLED = true  → Supabase Login aktiv
    // AUTH_ENABLED = false → Login übersprungen (dev/debug)
    private val AUTH_ENABLED = true

    // null = noch am Laden, false = nicht eingeloggt, true = eingeloggt
    // Wenn AUTH_ENABLED = false: direkt true setzen, nie null oder false
    private val _isLoggedIn = MutableStateFlow<Boolean?>(if (AUTH_ENABLED) null else true)
    val isLoggedIn: StateFlow<Boolean?> = _isLoggedIn

    init {
        if (AUTH_ENABLED) {
            viewModelScope.launch {
                // Falls der Supabase-Client nicht erzeugt werden kann (z.B. fehlende/leere
                // SUPABASE_URL/SUPABASE_ANON_KEY im Build), darf das die App nicht crashen -
                // stattdessen zeigen wir einfach den Login-Screen statt eines harten Absturzes.
                runCatching {
                    AuthRepository.awaitSession()
                    AuthRepository.isLoggedIn
                }.fold(
                    onSuccess = { loggedIn -> _isLoggedIn.value = loggedIn },
                    onFailure = { _isLoggedIn.value = false }
                )
            }
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
