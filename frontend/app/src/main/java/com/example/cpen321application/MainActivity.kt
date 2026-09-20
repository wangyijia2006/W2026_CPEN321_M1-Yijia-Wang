package com.example.cpen321application

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cpen321application.ui.theme.CPEN321ApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            CPEN321ApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    M1App(
                        apiBaseUrl = BuildConfig.API_BASE_URL,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
private fun M1App(
    apiBaseUrl: String,
    modifier: Modifier = Modifier
) {
    var currentPage by rememberSaveable { mutableStateOf("Home") }
    // Keep login in memory while navigating between the three features.
    val loginState = remember { LoginServerState() }

    // Put the state in the entire app to share the state together。
    var minutesInput by rememberSaveable { mutableStateOf("0") }
    var secondsInput by rememberSaveable { mutableStateOf("5") }
    var timerDeadline by rememberSaveable { mutableStateOf(0L) }
    var remainingSeconds by rememberSaveable { mutableStateOf(0L) }
    var inputError by rememberSaveable { mutableStateOf("") }
    var surpriseMessage by rememberSaveable { mutableStateOf("") }
    val isTimerRunning = timerDeadline != 0L

    // Calculate the remaining time by finishing time.
    LaunchedEffect(timerDeadline) {
        if (timerDeadline == 0L) return@LaunchedEffect
        val deadline = timerDeadline

        while (true) {
            val remainingMillis = (deadline - SystemClock.elapsedRealtime())
                .coerceAtLeast(0L)
            remainingSeconds = (remainingMillis + 999L) / 1_000L

            if (remainingMillis == 0L) {
                timerDeadline = 0L
                surpriseMessage = listOf(
                    "You found a tiny adventure!\nFind something blue nearby and imagine its secret superpower.",
                    "You unlocked a creativity break!\nInvent a superhero name for the nearest object.",
                    "A message from your future self:\nSmall steps count. Take a stretch and celebrate this one!"
                ).random()
                break
            }

            // use delay to avoid getting stuck
            delay(100L)
        }
    }

    BackHandler(enabled = surpriseMessage.isNotEmpty() || currentPage != "Home") {
        if (surpriseMessage.isNotEmpty()) {
            surpriseMessage = ""
        } else {
            currentPage = "Home"
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .then(
                    if (surpriseMessage.isNotEmpty()) Modifier.clearAndSetSemantics { }
                    else Modifier
                )
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = if (currentPage == "Home") "M1 App" else currentPage,
                style = MaterialTheme.typography.headlineMedium
            )

            if (isTimerRunning && currentPage != "Timer") {
                Text("Timer running: ${formatDuration(remainingSeconds)}")
            }

            if (currentPage == "Home") {
                Text("Choose a feature")

                Button(
                    onClick = { currentPage = "Login + Server" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login + Server")
                }

                Button(
                    onClick = { currentPage = "Live Updates" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Live Updates")
                }

                Button(
                    onClick = { currentPage = "Timer" },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Timer")
                }
            } else {
                OutlinedButton(onClick = { currentPage = "Home" }) {
                    Text("Back to Home")
                }

                when (currentPage) {
                    "Login + Server" -> {
                        LoginServerScreen(
                            apiBaseUrl = apiBaseUrl,
                            googleClientId = BuildConfig.GOOGLE_CLIENT_ID,
                            state = loginState
                        )
                    }

                    "Live Updates" -> {
                        LiveUpdatesScreen(apiBaseUrl)
                    }

                    "Timer" -> {
                        Text("Set a countdown to unlock a surprise.")

                        OutlinedTextField(
                            value = minutesInput,
                            onValueChange = {
                                minutesInput = it
                                inputError = ""
                            },
                            label = { Text("Minutes (0–9999)") },
                            enabled = !isTimerRunning,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = secondsInput,
                            onValueChange = {
                                secondsInput = it
                                inputError = ""
                            },
                            label = { Text("Seconds (0–59)") },
                            enabled = !isTimerRunning,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (inputError.isNotEmpty()) {
                            Text(inputError, color = MaterialTheme.colorScheme.error)
                        }

                        Text(
                            text = formatDuration(remainingSeconds),
                            style = MaterialTheme.typography.displayLarge
                        )

                        Button(
                            enabled = !isTimerRunning,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                // Deal with illegal input, showing message
                                val minutes = minutesInput.trim().ifEmpty { "0" }.toIntOrNull()
                                val seconds = secondsInput.trim().ifEmpty { "0" }.toIntOrNull()

                                if (minutes == null || seconds == null ||
                                    minutes !in 0..9999 || seconds !in 0..59
                                ) {
                                    inputError = "Use whole numbers: minutes 0–9999, seconds 0–59."
                                } else {
                                    val totalSeconds = minutes.toLong() * 60L + seconds
                                    if (totalSeconds == 0L) {
                                        inputError = "Enter a duration greater than zero."
                                    } else {
                                        inputError = ""
                                        surpriseMessage = ""
                                        remainingSeconds = totalSeconds
                                        timerDeadline = SystemClock.elapsedRealtime() + totalSeconds * 1_000L
                                    }
                                }
                            }
                        ) {
                            Text(if (isTimerRunning) "Counting down…" else "Start timer")
                        }

                        OutlinedButton(
                            enabled = isTimerRunning || remainingSeconds > 0L,
                            onClick = {
                                timerDeadline = 0L
                                remainingSeconds = 0L
                                inputError = ""
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Cancel timer")
                        }

                        Text("You can return to Home while the timer runs.")
                    }
                }
            }
        }

        // Display my surprising message on the screen
        if (surpriseMessage.isNotEmpty()) {
            SurpriseOverlay(
                message = surpriseMessage,
                onDismiss = { surpriseMessage = "" }
            )
        }
    }
}

@Composable
private fun SurpriseOverlay(message: String, onDismiss: () -> Unit) {
    // The overlay blocks clicks on the background;
    // use the button on the card or the back button to close it.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { }
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text("★  ✦  ★", style = MaterialTheme.typography.displaySmall)
                Text("Time's up!", style = MaterialTheme.typography.headlineMedium)
                Text("Your surprise card", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodyLarge)
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Got it!")
                }
            }
        }
    }
}

private fun formatDuration(totalSeconds: Long): String {
    val minutes = (totalSeconds / 60L).toString().padStart(2, '0')
    val seconds = (totalSeconds % 60L).toString().padStart(2, '0')
    return "$minutes:$seconds"
}

