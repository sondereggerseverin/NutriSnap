package com.nutrisnap.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.gotrue.providers.builtin.Email
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SupabaseClient.client.auth.currentSessionOrNull()
        if (session != null) { startMain(); return }
        setContent {
            MaterialTheme {
                LoginScreen(
                    onLogin = { email, password ->
                        lifecycleScope.launch {
                            try {
                                SupabaseClient.client.auth.signInWith(Email) {
                                    this.email = email; this.password = password
                                }
                                startMain()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@LoginActivity, "Login fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    onRegister = { email, password ->
                        lifecycleScope.launch {
                            try {
                                SupabaseClient.client.auth.signUpWith(Email) {
                                    this.email = email; this.password = password
                                }
                                android.widget.Toast.makeText(this@LoginActivity, "Konto erstellt! Bitte E-Mail bestaetigen.", android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@LoginActivity, "Registrierung fehlgeschlagen: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                )
            }
        }
    }
    private fun startMain() { startActivity(Intent(this, MainActivity::class.java)); finish() }
}

@Composable
fun LoginScreen(onLogin: (String, String) -> Unit, onRegister: (String, String) -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val green = Color(0xFF2E7D32)
    Column(modifier = Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("NutriSnap", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = green)
        Spacer(Modifier.height(32.dp))
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("E-Mail") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), singleLine = true)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Passwort") }, modifier = Modifier.fillMaxWidth(), singleLine = true, visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), trailingIcon = { IconButton(onClick = { passwordVisible = !passwordVisible }) { Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null) } })
        Spacer(Modifier.height(24.dp))
        Button(onClick = { onLogin(email, password) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = green)) { Text("Anmelden") }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = { onRegister(email, password) }, modifier = Modifier.fillMaxWidth()) { Text("Konto erstellen", color = green) }
    }
}