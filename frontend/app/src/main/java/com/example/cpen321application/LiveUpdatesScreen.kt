package com.example.cpen321application

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.socket.client.IO
import org.json.JSONObject
import io.socket.client.Socket

@Composable
fun LiveUpdatesScreen(apiBaseUrl: String) {
    val pixels = remember { mutableStateListOf<Color>().apply { repeat(256) { add(Color.White) } } }
    var status by remember { mutableStateOf("Connecting to backend...") }
    var pixelCount by remember { mutableStateOf(0) }
    var failure by remember { mutableStateOf("") }

    DisposableEffect(apiBaseUrl) {
        val mainHandler = Handler(Looper.getMainLooper())
        var active = true
        var lastPixelAt = 0L
        val seen = BooleanArray(256)
        val options = IO.Options().apply {
            transports = arrayOf("websocket")
            reconnection = true
            reconnectionDelay = 1000
            reconnectionDelayMax = 10000
            timeout = 10000
            forceNew = true
        }
        val socket = IO.socket(apiBaseUrl.trimEnd('/'), options)

        // Socket.IO callbacks run off the UI thread; change Compose state on main.
        fun onMain(action: () -> Unit) {
            mainHandler.post { if (active) action() }
        }

        fun clearGrid() {
            for (index in 0 until 256) pixels[index] = Color.White
            seen.fill(false)
            pixelCount = 0
            lastPixelAt = 0L
        }

        socket.on(Socket.EVENT_CONNECT) {
            onMain {
                clearGrid()
                failure = ""
                status = "Backend connected. Waiting for course server..."
            }
        }
        socket.on(Socket.EVENT_CONNECT_ERROR) {
            onMain { status = "Cannot connect to backend. Retrying..." }
        }
        socket.on(Socket.EVENT_DISCONNECT) {
            onMain { status = "Backend disconnected. Retrying..." }
        }
        socket.on("stream-reset") { onMain { clearGrid() } }
        socket.on("stream-status") { args ->
            val message = args.firstOrNull() as? String
            if (message != null) onMain { status = message }
        }
        socket.on("pixel") { args ->
            val raw = args.firstOrNull() as? String
            val receivedAt = SystemClock.elapsedRealtime()
            try {
                require(raw != null) { "Expected a JSON string" }
                val update = JSONObject(raw)
                val xValue = update.get("x")
                val yValue = update.get("y")
                require(xValue is Int && yValue is Int) { "Coordinates must be integers" }
                require(xValue in 0..15 && yValue in 0..15) { "Pixel is outside the grid" }
                val hex = update.getString("color")
                require(Regex("^#?[0-9a-fA-F]{6}$").matches(hex)) { "Expected an RGB hex color" }
                val color = Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
                val index = yValue * 16 + xValue

                onMain {
                    // The protocol has no frame ID. Use the specified 5-second
                    // pause to detect the first pixel of the next image.
                    if (lastPixelAt != 0L && receivedAt - lastPixelAt > 4000L) clearGrid()
                    pixels[index] = color
                    if (!seen[index]) { seen[index] = true; pixelCount += 1 }
                    lastPixelAt = receivedAt
                    failure = ""
                    status = "Receiving live pixels"
                }
            } catch (_: Exception) {
                onMain { failure = "Received an invalid pixel; waiting for the next update." }
            }
        }
        socket.connect()

        onDispose {
            active = false
            socket.off()
            socket.disconnect()
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Live Pixel Art", style = MaterialTheme.typography.titleLarge)
        Text(status)
        if (failure.isNotEmpty()) Text(failure, color = MaterialTheme.colorScheme.error)
        Canvas(
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics {
                contentDescription = "Live pixel art on a 16 by 16 grid, $pixelCount cells painted"
            }
        ) {
            val cell = size.width / 16f
            pixels.forEachIndexed { index, color ->
                drawRect(color, Offset((index % 16) * cell, (index / 16) * cell), Size(cell, cell))
            }
            for (line in 0..16) {
                val position = line * cell
                drawLine(Color.LightGray, Offset(position, 0f), Offset(position, size.height), 1f)
                drawLine(Color.LightGray, Offset(0f, position), Offset(size.width, position), 1f)
            }
        }
        Text("$pixelCount / 256 cells painted")
        Text("The next picture starts automatically after a short pause.")
    }
}

