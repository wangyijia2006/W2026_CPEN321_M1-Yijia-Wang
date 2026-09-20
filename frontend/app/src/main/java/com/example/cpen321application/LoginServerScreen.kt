package com.example.cpen321application

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal data class SignedInUser(val firstName: String, val lastName: String, val displayName: String)

internal data class ServerDetails(
    val serverIp: String,
    val clientIp: String,
    val serverTime: String,
    val clientTime: String,
    val developerFirstName: String,
    val developerLastName: String
)

internal class LoginServerState {
    var user by mutableStateOf<SignedInUser?>(null)
        private set
    var details by mutableStateOf<ServerDetails?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set
    // Do not put the token in saved state, logs, source files or URLs.
    private var idToken = ""

    suspend fun signIn(context: Context, apiBaseUrl: String, clientId: String) {
        if (busy) return
        if (clientId.isBlank()) {
            message = "Google sign-in is not configured."
            return
        }
        busy = true
        message = ""
        try {
            val activity = context.findActivity()
                ?: throw IOException("Please open the app again to sign in.")
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
                .build()
            val result = CredentialManager.create(activity).getCredential(activity, request)
            val credential = result.credential
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw IOException("Google returned an unsupported sign-in response.")
            }
            val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
            val response = requestJson(apiBaseUrl, "/api/auth/google", token, "POST")
                .getJSONObject("user")
            // Display names only after the backend has verified Google's token.
            idToken = token
            user = SignedInUser(
                response.optString("firstName"),
                response.optString("lastName"),
                response.optString("displayName")
            )
            loadDetails(apiBaseUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: GetCredentialCancellationException) {
            message = "Sign-in was cancelled. You can try again."
        } catch (_: NoCredentialException) {
            message = "No Google account is available. Add one in the phone's Settings and try again."
        } catch (_: GetCredentialException) {
            message = "Google sign-in failed. Check the internet connection and Google account, then try again."
        } catch (error: Exception) {
            handleFailure(error)
        } finally {
            busy = false
        }
    }

    suspend fun refresh(apiBaseUrl: String) {
        if (busy || idToken.isBlank()) return
        busy = true
        message = ""
        try {
            loadDetails(apiBaseUrl)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            handleFailure(error)
        } finally {
            busy = false
        }
    }

    suspend fun signOut(context: Context) {
        if (busy) return
        busy = true
        clearSession()
        message = ""
        try {
            CredentialManager.create(context).clearCredentialState(ClearCredentialStateRequest())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            message = "Signed out of this app. Google account preferences could not be cleared."
        } finally {
            busy = false
        }
    }

    private suspend fun loadDetails(apiBaseUrl: String) = coroutineScope {
        details = null
        val ipRequest = async { requestJson(apiBaseUrl, "/api/server/ip", idToken) }
        val timeRequest = async {
            val server = requestJson(apiBaseUrl, "/api/server/time", idToken)
            val clientTime = ZonedDateTime.now().format(
                DateTimeFormatter.ofPattern("HH:mm:ss 'GMT'xxx", Locale.US)
            )
            server.getString("serverTime") to clientTime
        }
        val nameRequest = async { requestJson(apiBaseUrl, "/api/developer", idToken) }
        val ip = ipRequest.await()
        val (serverTime, clientTime) = timeRequest.await()
        val developer = nameRequest.await()
        details = ServerDetails(
            ip.getString("serverIp"), ip.getString("clientIp"), serverTime, clientTime,
            developer.getString("firstName"), developer.getString("lastName")
        )
    }

    private fun clearSession() {
        idToken = ""
        user = null
        details = null
    }

    private fun handleFailure(error: Exception) {
        if (error is BackendException && error.status == 401) clearSession()
        message = when (error) {
            is BackendException -> error.message ?: "Backend request failed."
            is JSONException -> "The backend response was unexpected. Check that the latest backend is deployed."
            is IOException -> "Cannot reach the HTTPS backend. Check your connection and try again."
            else -> "Sign-in could not be completed. Please try again."
        }
    }
}

@Composable
internal fun LoginServerScreen(apiBaseUrl: String, googleClientId: String, state: LoginServerState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = state.user
    val details = state.details

    if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    if (user == null) {
        Text("Sign in with Google to view the server and client information.")
        OutlinedButton(
            enabled = !state.busy,
            onClick = { scope.launch { state.signIn(context, apiBaseUrl, googleClientId) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign in with Google")
        }
    } else {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Google account", style = MaterialTheme.typography.titleLarge)
                InfoField("First name", user.firstName.ifBlank { "Not provided by Google" })
                InfoField("Last name", user.lastName.ifBlank { "Not provided by Google" })
                if (user.firstName.isBlank() && user.lastName.isBlank() && user.displayName.isNotBlank()) {
                    InfoField("Display name", user.displayName)
                }
            }
        }
        if (details != null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Server and client", style = MaterialTheme.typography.titleLarge)
                    InfoField("Server public IP", details.serverIp)
                    InfoField("Client IP", details.clientIp)
                    InfoField("Server local time", details.serverTime)
                    InfoField("Client local time", details.clientTime)
                    InfoField("Developer first name", details.developerFirstName)
                    InfoField("Developer last name", details.developerLastName)
                }
            }
            Text("Times were captured when the information was requested.")
        }
        Button(
            enabled = !state.busy,
            onClick = { scope.launch { state.refresh(apiBaseUrl) } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Refresh information") }
        OutlinedButton(
            enabled = !state.busy,
            onClick = { scope.launch { state.signOut(context) } },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Sign out") }
    }
    if (state.message.isNotBlank()) {
        Text(state.message, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun InfoField(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}

private class BackendException(val status: Int, message: String) : IOException(message)

private suspend fun requestJson(
    apiBaseUrl: String,
    path: String,
    token: String,
    method: String = "GET"
): JSONObject = withContext(Dispatchers.IO) {
    val endpoint = URL("${apiBaseUrl.trimEnd('/')}$path")
    if (endpoint.protocol != "https") {
        throw BackendException(0, "This feature needs an HTTPS backend address.")
    }
    val connection = endpoint.openConnection() as HttpURLConnection
    try {
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Authorization", "Bearer $token")
        val status = connection.responseCode
        ensureActive()
        if (status !in 200..299) {
            val message = when (status) {
                401 -> "Sign-in expired or could not be verified. Please sign in again."
                404 -> "This backend does not have the Login APIs yet. Deploy the latest backend first."
                503 -> "The backend is not ready. Check its Google client ID and server settings."
                else -> "Backend request failed (HTTP $status). Please try again."
            }
            throw BackendException(status, message)
        }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        ensureActive()
        JSONObject(text)
    } finally {
        connection.disconnect()
    }
}
