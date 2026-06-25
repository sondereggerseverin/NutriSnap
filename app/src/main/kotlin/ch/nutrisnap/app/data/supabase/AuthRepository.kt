package ch.nutrisnap.app.data.supabase

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

object AuthRepository {
    private val auth get() = SupabaseClient.client.auth

    val currentUser: UserInfo? get() = auth.currentUserOrNull()
    val isLoggedIn: Boolean get() = currentUser != null

    /** Wartet bis Supabase die gespeicherte Session wiederhergestellt hat. */
    suspend fun awaitSession() {
        // Mit autoLoadFromStorage=true lädt Supabase die Session beim Client-Init.
        // Kurze Verzögerung reicht damit der Auth-State gesetzt ist.
        runCatching { auth.awaitInitialization() }
    }

    suspend fun signUp(email: String, password: String) {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut() {
        auth.signOut()
    }
}
