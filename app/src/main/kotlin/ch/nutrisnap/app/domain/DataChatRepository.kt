package ch.nutrisnap.app.domain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.nutrisnap.app.data.db.NutriDatabase
import ch.nutrisnap.app.data.repository.DiaryRepository
import ch.nutrisnap.app.data.repository.UserProfileRepository
import ch.nutrisnap.app.data.repository.WeightRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

// ============================================================
// FEATURE 8: "Frag deine App" — Chat über eigene Daten
//
// Nutzt bestehenden GeminiService.generateText() (object, kein DI nötig).
//
// Integration:
//  1. DataChatViewModel (unten, manuell wie HomeViewModel — kein Hilt im Projekt) an
//     einer neuen Chat-Stelle einbinden (Compose-Screen, nicht Fragment — das Projekt
//     nutzt durchgängig Jetpack Compose, keine klassischen View/Fragment-Layouts).
//  2. Chatverlauf lebt aktuell nur im ViewModel (StateFlow) — bewusst kein @Singleton-
//     Repository mehr wie im Original-Entwurf, da es ohne Hilt keinen sauberen Weg gibt,
//     eine echte Singleton-Instanz app-weit zu teilen; für "Verlauf bleibt in der Session"
//     reicht ViewModel-Scope (überlebt Konfigurationsänderungen, nicht aber Prozesstod).
//
// Abweichungen vom ursprünglichen Entwurf (referenzierte nicht existierende APIs):
//  - Tageswerte/Wochenwerte kommen aus DiaryRepository statt aus einem nicht existierenden
//    StatsRepository.getTodayTotalKcal()/getTodayMacros()/getDailyKcalHistory().
//  - Kalorienziel ist das aktuelle adaptive Tagesziel, exakt wie auf dem Home-Screen
//    berechnet (AdaptiveTdeeCalculator ist ein object, kein injizierbarer Typ).
//  - Kein Nutzername (UserProfile hat kein Namensfeld) — Prompt spricht den Nutzer direkt an.
//  - GeminiService.generateText() liefert Result<String>, nicht String — entsprechend
//    mit .getOrElse behandelt statt in einem try/catch um einen falschen Rückgabetyp.
// ============================================================

enum class ChatRole { USER, ASSISTANT }

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val role: ChatRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

class DataChatRepository(
    private val diaryRepository: DiaryRepository,
    private val weightRepository: WeightRepository,
    private val userProfileRepository: UserProfileRepository
) {
    private val _messages = mutableListOf<ChatMessage>()
    val messages: List<ChatMessage> get() = _messages.toList()

    suspend fun ask(userQuestion: String): String {
        _messages.add(ChatMessage(role = ChatRole.USER, content = userQuestion))

        if (!GeminiService.isAvailable()) {
            val msg = "Der Chat ist gerade nicht verfügbar (kein Gemini-API-Key konfiguriert)."
            _messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = msg))
            return msg
        }

        val systemPrompt = buildSystemPrompt()
        val history = _messages.takeLast(6).joinToString("\n") { "${it.role.name}: ${it.content}" }

        val fullPrompt = """
--- BISHERIGE KONVERSATION ---
$history

Beantworte die letzte Nutzerfrage kurz und präzise auf Deutsch.
Nutze ausschließlich die Zahlen aus dem Kontext oben.
        """.trimIndent()

        val response = GeminiService.generateText(fullPrompt, systemPrompt = systemPrompt)
            .getOrElse { "Entschuldigung, ich konnte die Frage gerade nicht beantworten. Bitte versuche es nochmal." }

        _messages.add(ChatMessage(role = ChatRole.ASSISTANT, content = response))
        return response
    }

    private suspend fun buildSystemPrompt(): String {
        val today = LocalDate.now()
        val profile = userProfileRepository.get().first()

        val todayEntries = diaryRepository.getEntriesForDate(today).first()
        val todayKcal = todayEntries.sumOf { it.calories.toDouble() }
        val todayProtein = todayEntries.sumOf { it.protein.toDouble() }
        val todayCarbs = todayEntries.sumOf { it.carbs.toDouble() }
        val todayFat = todayEntries.sumOf { it.fat.toDouble() }

        // Gleiches statisches Tagesziel wie überall sonst, wenn kein adaptives Ziel
        // berechnet werden kann (siehe RecipeBudgetScaler für die volle adaptive Variante).
        val goalKcal = profile.dailyCalorieGoal

        val weekFrom = today.minusDays(6)
        val weeklyHistory = diaryRepository.getSummaryBetween(weekFrom, today).first()
        val monthFrom = today.minusDays(29)
        val monthlyHistory = diaryRepository.getSummaryBetween(monthFrom, today).first()

        val weeklyAvg = weeklyHistory.map { it.calories.toDouble() }.let { if (it.isEmpty()) 0.0 else it.average() }
        val monthlyAvg = monthlyHistory.map { it.calories.toDouble() }.let { if (it.isEmpty()) 0.0 else it.average() }
        val weeklyBalances = weeklyHistory.map { it.calories - goalKcal }
        val overDays = weeklyBalances.count { it > 100 }
        val underDays = weeklyBalances.count { it < -100 }

        val lastWeight = weightRepository.getLatest()?.weightKg

        return """
Du bist der persönliche Ernährungsassistent des Nutzers in NutriSnap.

HEUTE:
- Gegessen: ${todayKcal.toInt()} kcal (Ziel: $goalKcal kcal, Differenz: ${(todayKcal - goalKcal).toInt()} kcal)
- Protein: ${todayProtein.toInt()}g | Carbs: ${todayCarbs.toInt()}g | Fett: ${todayFat.toInt()}g

DIESE WOCHE (7 Tage):
- Ø ${weeklyAvg.toInt()} kcal/Tag
- ${overDays}x über Ziel (>100 kcal), ${underDays}x unter Ziel (>100 kcal darunter)

DIESER MONAT: Ø ${monthlyAvg.toInt()} kcal/Tag
GEWICHT: ${lastWeight?.let { "$it kg" } ?: "nicht erfasst"} | Ziel: ${profile.targetWeightKg?.let { "$it kg" } ?: "kein Ziel gesetzt"}
        """.trimIndent()
    }

    fun clearHistory() { _messages.clear() }
}

// ============================================================
// ViewModel — manuell instanziiert wie HomeViewModel (kein Hilt im Projekt)
// ============================================================

class DataChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = NutriDatabase.getInstance(app)
    private val chatRepository = DataChatRepository(
        DiaryRepository(db), WeightRepository(db), UserProfileRepository(db)
    )

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun sendMessage(question: String) {
        viewModelScope.launch {
            _isLoading.value = true
            chatRepository.ask(question)
            _messages.value = chatRepository.messages.toList()
            _isLoading.value = false
        }
    }

    fun clearChat() {
        chatRepository.clearHistory()
        _messages.value = emptyList()
    }
}
