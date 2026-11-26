package com.rutacomercial.app.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

/** Shown only when the build carries the Google web client id (provider enabled in Firebase). */
@Composable
internal fun OwnerGoogleAccount(cloud: CloudSync) {
    val context = LocalContext.current
    val webClient = remember {
        context.resources.getIdentifier("default_web_client_id", "string", context.packageName).takeIf { it != 0 }?.let(context::getString)
    } ?: return
    val account by cloud.account.collectAsState()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    account?.let { Text("✓ Cuenta de Owner respaldada · $it", style = MaterialTheme.typography.bodySmall); return }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Respaldá tu cuenta de Owner", style = MaterialTheme.typography.titleSmall)
            Text("Con Google, si cambiás o reinstalás el teléfono, tus repartidores siguen vinculados.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy, onClick = {
                busy = true; error = ""
                scope.launch {
                    try {
                        val request = GetCredentialRequest.Builder().addCredentialOption(GetSignInWithGoogleOption.Builder(webClient).build()).build()
                        val credential = CredentialManager.create(context).getCredential(context as Activity, request).credential
                        require(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                        cloud.useGoogle(GoogleIdTokenCredential.createFrom(credential.data).idToken)
                    } catch (_: GetCredentialCancellationException) {
                    } catch (e: Exception) { error = "No se pudo conectar con Google. Revisá la conexión e intentá de nuevo." }
                    finally { busy = false }
                }
            }) { Text("Conectar con Google") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
}
