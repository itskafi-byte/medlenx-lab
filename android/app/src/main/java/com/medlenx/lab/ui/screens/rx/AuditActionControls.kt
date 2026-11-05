package com.medlenx.lab.ui.screens.rx

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@Composable
fun DeleteScanButton(enabled: Boolean, onDelete: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    IconButton(onClick = { confirm = true }, enabled = enabled) {
        Icon(Icons.Filled.Delete, contentDescription = "Delete scan")
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Delete this scan?") },
        text = { Text("This removes the scan and its medicine references from this device and excludes it from local analytics. The photo and any cloud copy remain; the cloud copy will not be restored to this account on this device.") },
        confirmButton = { TextButton(onClick = { confirm = false; onDelete() }, enabled = enabled) { Text("Delete scan") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}

@Composable
fun DuplicateOverrideButton(enabled: Boolean, onApprove: () -> Unit) {
    OutlinedButton(onClick = onApprove, enabled = enabled) { Text("Override (Testing)") }
}
