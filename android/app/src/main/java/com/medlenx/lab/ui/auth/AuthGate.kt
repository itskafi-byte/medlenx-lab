package com.medlenx.lab.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.sync.ProfileCloudSync
import com.medlenx.lab.data.sync.TrustedMembership
import com.medlenx.lab.data.sync.UploadScheduling
import kotlinx.coroutines.CancellationException
import com.medlenx.lab.ui.shell.BrandLockup
import com.medlenx.lab.ui.theme.Mlx

@Composable
fun AuthGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val auth = remember { TrustedMembership.firebase(context)?.let { FirebaseAuth.getInstance(it) } }
    var uid by remember(auth) { mutableStateOf(auth?.currentUser?.uid) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { uid = it.currentUser?.uid }
        auth?.addAuthStateListener(listener)
        onDispose { auth?.removeAuthStateListener(listener) }
    }
    val currentUid = uid
    remember(currentUid) {
        if (currentUid != null && currentUid != "legacy_user") ProfileCloudSync.startSession(currentUid)
    }
    LaunchedEffect(currentUid) {
        if (currentUid != null && currentUid != "legacy_user") {
            runCatching { UploadScheduling.start(context, currentUid) }
            try {
                TrustedMembership.refresh(context, currentUid)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: keep the last server-verified snapshot for this UID.
            }
        }
    }
    LaunchedEffect(currentUid) {
        if (currentUid != null && currentUid != "legacy_user") {
            try { ProfileCloudSync.watch(context.applicationContext, currentUid) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { }
        }
    }
    DisposableEffect(currentUid) {
        val graph = (context.applicationContext as MedLenXApp).graph
        graph.deviceState.bindUser(currentUid, graph.scope)
        onDispose { graph.deviceState.bindUser(null, graph.scope) }
    }
    if (currentUid == null) {
        AuthScreen(auth)
    } else if (currentUid == "legacy_user") {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("This account ID is reserved for unassigned legacy data. Contact your administrator.")
            Button(onClick = { auth?.signOut() }) { Text("Sign out") }
        }
    } else {
        // Activity-owned ViewModels survive sign-out. Give each authenticated
        // session its own store so old screen state is discarded on account change.
        val owner = remember(currentUid) {
            object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
        }
        DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
    }
}

@Composable
private fun AuthScreen(auth: FirebaseAuth?) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var create by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    Box(
        modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Mlx.Brand900, Mlx.Brand700, Mlx.Brand900)))
            .statusBarsPadding().navigationBarsPadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                BrandLockup(tileSize = 56.dp)
                Text(if (create) "Create your account" else "Welcome back", style = MaterialTheme.typography.headlineMedium)
                Text("Your prescription intelligence workspace", style = MaterialTheme.typography.bodyLarge)
                if (auth == null) {
                    Text("Firebase is not configured. Add your Firebase project settings to android/local.properties and rebuild.")
                } else {
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = {
                        if (email.isBlank() || password.isBlank()) {
                            message = "Enter your email and password."
                        } else {
                            busy = true
                            message = ""
                            val task = if (create) auth.createUserWithEmailAndPassword(email.trim(), password)
                                else auth.signInWithEmailAndPassword(email.trim(), password)
                            task.addOnCompleteListener { result ->
                                busy = false
                                if (!result.isSuccessful) message = result.exception?.localizedMessage ?: "Unable to authenticate. Try again."
                            }
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = Mlx.Brand900, contentColor = Color.White)) {
                        if (busy) CircularProgressIndicator(color = Color.White) else Text(if (create) "Create account" else "Sign in", color = Color.White)
                    }
                    TextButton(onClick = { create = !create; message = "" }, enabled = !busy) {
                        Text(if (create) "Already have an account? Sign in" else "New to MedLenX? Create an account")
                    }
                    TextButton(onClick = {
                        if (email.isBlank()) message = "Enter your email first."
                        else {
                            busy = true
                            auth.sendPasswordResetEmail(email.trim()).addOnCompleteListener {
                                busy = false
                                message = if (it.isSuccessful) "If the address is registered, a reset email will be sent."
                                    else "Could not send a reset email. Check the address and try again."
                            }
                        }
                    }, enabled = !busy) { Text("Forgot password?") }
                    Text("This device keeps local data shared between sign-ins. Use only on a single-user device.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
