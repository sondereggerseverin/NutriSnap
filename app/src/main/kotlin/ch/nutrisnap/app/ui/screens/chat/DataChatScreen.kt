package ch.nutrisnap.app.ui.screens.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.nutrisnap.app.domain.ChatMessage
import ch.nutrisnap.app.domain.ChatRole
import ch.nutrisnap.app.domain.DataChatViewModel
import ch.nutrisnap.app.ui.theme.NutriRadius
import ch.nutrisnap.app.ui.theme.NutriSpacing

/** Starter-Fragen analog Fud AI Coach (Ziel- und Makro-Kontext). */
private val COACH_STARTER_CHIPS = listOf(
    "Was soll ich zum Abendessen essen?",
    "Welches Gewicht in 30 Tagen?",
    "Wie viel Protein habe ich heute noch übrig?",
    "Wie war meine letzte Woche?"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataChatScreen(
    onBack: (() -> Unit)? = null,
    vm: DataChatViewModel = viewModel()
) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val isLoading by vm.isLoading.collectAsStateWithLifecycle()
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1 + if (isLoading) 1 else 0)
        }
    }

    fun send(text: String = question) {
        val q = text.trim()
        if (q.isEmpty() || isLoading) return
        question = ""
        vm.sendMessage(q)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Coach",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Chat zurücksetzen")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background)
                    .navigationBarsPadding()
                    .padding(bottom = NutriSpacing.sm)
            ) {
                // Vorschlags-Chips (wie Fud)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = NutriSpacing.md, vertical = NutriSpacing.xs),
                    horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
                ) {
                    COACH_STARTER_CHIPS.forEach { chip ->
                        SuggestionChip(
                            onClick = { send(chip) },
                            enabled = !isLoading,
                            label = {
                                Text(chip, fontSize = 13.sp, maxLines = 1)
                            },
                            icon = {
                                Icon(
                                    Icons.Default.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            },
                            colors = SuggestionChipDefaults.suggestionChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
                // Eingabezeile: Galerie · Kamera · Text · Mic · Senden
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = NutriSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(
                        onClick = { /* Foto-Anhang folgt */ },
                        enabled = false,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = "Bild aus Galerie",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { /* Kamera-Anhang folgt */ },
                        enabled = false,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.CameraAlt,
                            contentDescription = "Foto aufnehmen",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedTextField(
                        value = question,
                        onValueChange = { question = it },
                        modifier = Modifier.weight(1f),
                        placeholder = {
                            Text("Coach fragen…", fontSize = 14.sp)
                        },
                        shape = RoundedCornerShape(24.dp),
                        singleLine = true,
                        enabled = !isLoading,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    )
                    IconButton(
                        onClick = { /* Sprach-Eingabe folgt */ },
                        enabled = false,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "Spracheingabe",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    FilledIconButton(
                        onClick = { send() },
                        enabled = !isLoading && question.isNotBlank(),
                        modifier = Modifier.size(40.dp),
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Senden",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    ) { padding ->
        if (messages.isEmpty() && !isLoading) {
            // Leerer Zustand: prominenter Starter-Chip oben (Fud-Stil)
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = NutriSpacing.lg),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(24.dp))
                SuggestionChip(
                    onClick = { send(COACH_STARTER_CHIPS.first()) },
                    label = {
                        Text(
                            COACH_STARTER_CHIPS.first(),
                            fontWeight = FontWeight.Medium,
                            fontSize = 14.sp
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Frag den Coach zu Makros, Gewicht oder Mahlzeiten.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(
                    horizontal = NutriSpacing.lg,
                    vertical = NutriSpacing.md
                ),
                verticalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
            ) {
                items(messages, key = { it.id }) { msg ->
                    CoachBubble(msg)
                }
                if (isLoading) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(NutriSpacing.sm)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Denkt nach…",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachBubble(msg: ChatMessage) {
    val isUser = msg.role == ChatRole.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!isUser) {
            Box(
                Modifier
                    .padding(end = 8.dp, top = 4.dp)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Text(
                msg.content,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = if (isUser) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}
