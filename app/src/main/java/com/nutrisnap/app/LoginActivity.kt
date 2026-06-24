package com.nutrisnap.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nutrisnap.app.databinding.ActivityLoginBinding
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val session = SupabaseClient.client.auth.currentSessionOrNull()
        if (session != null) { startMain(); return }
        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            if (email.isEmpty() || password.isEmpty()) { Toast.makeText(this, "Bitte E-Mail und Passwort eingeben", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            lifecycleScope.launch { try { SupabaseClient.client.auth.signInWith(Email) { this.email = email; this.password = password }; startMain() } catch (e: Exception) { Toast.makeText(this@LoginActivity, "Login fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString()
            if (email.isEmpty() || password.isEmpty()) { Toast.makeText(this, "Bitte E-Mail und Passwort eingeben", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            lifecycleScope.launch { try { SupabaseClient.client.auth.signUpWith(Email) { this.email = email; this.password = password }; Toast.makeText(this@LoginActivity, "Konto erstellt! Bitte E-Mail bestaetigen.", Toast.LENGTH_LONG).show() } catch (e: Exception) { Toast.makeText(this@LoginActivity, "Registrierung fehlgeschlagen: ${e.message}", Toast.LENGTH_LONG).show() } }
        }
    }
    private fun startMain() { startActivity(Intent(this, MainActivity::class.java)); finish() }
}