package com.medlenx.lab

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.ui.auth.AuthGate
import com.medlenx.lab.ui.shell.MedLenXShell
import com.medlenx.lab.ui.shell.TopBarState
import com.medlenx.lab.ui.theme.MedLenXTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as MedLenXApp).graph
        val deviceState = graph.deviceState

        setContent {
            MedLenXTheme {
                AuthGate {
                MedLenXShell(
                    topBarState = TopBarState(
                        online = deviceState.online,
                        pendingUploads = deviceState.pendingUploadsFor(FirebaseAuth.getInstance().currentUser?.uid),
                    ),
                    appGraph = graph,
                )
                }
            }
        }
    }
}
