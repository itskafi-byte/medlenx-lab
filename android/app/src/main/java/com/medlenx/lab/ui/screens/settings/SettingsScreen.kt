package com.medlenx.lab.ui.screens.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.local.AvatarStore
import com.medlenx.lab.ui.components.OfficerAvatar
import com.medlenx.lab.ui.components.CompanyBadge
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxErrorLine
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.ProgressTrack
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/** `SettingsScreen` in `App.tsx:1974`. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
    onOpenHelp: () -> Unit = {},
) {
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.selectAvatar(uri)
    }
    var showAvatars by remember { mutableStateOf(false) }
    if (showAvatars) {
        AlertDialog(onDismissRequest = { showAvatars = false }, title = { Text("Choose an avatar") },
            text = {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarStore.ids.forEach { id ->
                        OfficerAvatar(imageUri = null, avatarId = id, size = 64.dp,
                            modifier = Modifier.border(if (vm.avatarId == id) 3.dp else 0.dp, Mlx.Brand600, androidx.compose.foundation.shape.CircleShape)
                                .clickable { vm.selectBuiltInAvatar(id); showAvatars = false })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAvatars = false }) { Text("Close") } })
    }
    vm.purgeConfirmationCount?.let { count ->
        AlertDialog(
            onDismissRequest = vm::cancelPurge,
            title = { Text("Clear Trapped Local Data?") },
            text = { Text("Permanently delete $count local audits and their photos where safe? This includes ALL pending, local-only, and rejected audits for EVERY account on this device, plus all unassigned legacy audits. Unsynced data will be lost, including other users' data. Shared photos may be retained. Cloud records are not deleted, and an upload already in progress may still finish. This cannot be undone.") },
            confirmButton = { TextButton(onClick = vm::confirmPurge) { Text("Delete permanently") } },
            dismissButton = { TextButton(onClick = vm::cancelPurge) { Text("Cancel") } },
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MlxD.SectionGap),
    ) {
        MlxCard {
            Text(
                text = "Enterprise Settings & Officer Profile",
                style = MlxType.PanelTitle,
                color = Mlx.Text900,
            )
            Text(
                text = "Onboard your company, identity card and monthly brand targets. " +
                    "Vision-model hits on those brands become KPI progress automatically.",
                style = MlxType.BodySmall,
                color = Mlx.Text500,
                modifier = Modifier.padding(bottom = MlxD.Space4),
            )

            FieldLabel("Profile Avatar")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OfficerAvatar(imageUri = vm.avatarUri, avatarId = vm.avatarId, size = 56.dp,
                    modifier = Modifier.clickable { showAvatars = true })
                Column {
                    OutlinedButton(onClick = { showAvatars = true }) { Text("Choose avatar") }
                    TextButton(onClick = {
                        avatarPicker.launch(PickVisualMediaRequest.Builder()
                            .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly).build())
                    }) { Text("Use local photo") }
                }
            }
            Text("Built-in avatars sync to your team. Your own photo stays on this device; teammates see your selected built-in avatar.",
                style = MlxType.Meta, color = Mlx.Text500)
            if (vm.avatarSyncPending) Text("Saved locally; team avatar sync is pending.", style = MlxType.Meta, color = Mlx.Text500)
            Text("When an officer has no avatar, their display name may be sent to ui-avatars.com to generate one.",
                style = MlxType.Meta, color = Mlx.Text500)
            vm.avatarError?.let { MlxErrorLine(it) }
            Spacer(Modifier.height(MlxD.Space4))

            FieldLabel("Pharmaceutical Company")
            MlxTextField(
                value = vm.companyQuery,
                onValueChange = vm::onCompanyQuery,
                placeholder = "Search Square, Beximco, Incepta...",
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Selected: ${vm.company.ifBlank { "—" }}",
                style = MlxType.BodySmall,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space1, bottom = MlxD.Space1),
            )
            if (vm.companyMatches.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mlx.Surface)
                        .border(BorderStroke(1.dp, Mlx.Brand200), RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState()),
                ) {
                    vm.companyMatches.forEach { name ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.selectCompany(name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CompanyBadge(name = name, size = 20.dp)
                            Spacer(Modifier.width(MlxD.Space2))
                            Text(text = name, style = MlxType.BodySmall, color = Mlx.Text900)
                        }
                    }
                }
            }

            Spacer(Modifier.height(MlxD.Space4))

            // The export puts the two long labels on a full-width row; on a phone
            // every field is full width, so the grid collapses to a single column.
            FieldLabel("Employee ID")
            MlxTextField(
                value = vm.employeeId,
                onValueChange = vm::updateEmployeeId,
                placeholder = "MR001",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            FieldLabel("Full Name")
            MlxTextField(
                value = vm.fullName,
                onValueChange = vm::updateFullName,
                placeholder = "Your name",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            SettingsDropdown("Division", vm.division, vm.divisions, vm::updateDivision)
            Spacer(Modifier.height(MlxD.Space3))
            SettingsDropdown(
                "Designated Territory / Zone", vm.territory, vm.territories,
                vm::updateTerritory, enabled = vm.division.isNotBlank(),
                placeholder = if (vm.division.isBlank()) "Select a division first" else "Select a territory",
            )
            Spacer(Modifier.height(MlxD.Space3))
            SettingsDropdown("Assigned Product Portfolio", vm.portfolio, vm.portfolios, vm::updatePortfolio)
            Spacer(Modifier.height(MlxD.Space3))
            SettingsDropdown(
                label = "Role", value = vm.role, options = vm.roles,
                onSelected = vm::updateRole, display = { vm.roleLabels[it] ?: it },
            )

            Spacer(Modifier.height(MlxD.Space4))

            FieldLabel("Daily Scan Target")
            MlxTextField(value = vm.dailyTarget, onValueChange = vm::updateDailyTarget,
                keyboardType = KeyboardType.Number, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(MlxD.Space4))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Monthly Brand Targets",
                    style = MlxType.SectionLabel,
                    color = Mlx.Text500,
                    modifier = Modifier.weight(1f),
                )
                MlxButton(
                    text = "Add brand",
                    onClick = vm::addBrand,
                    tone = ButtonTone.Outline,
                    icon = Icons.Filled.Add,
                )
            }
            Spacer(Modifier.height(MlxD.Space2))

            if (vm.targets.isEmpty()) {
                MlxEmptyState(
                    message = "No brand targets yet — add one and scanned hits become KPI progress.",
                    icon = Icons.Filled.Add,
                )
            } else {
                vm.targets.forEachIndexed { i, t ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
                    ) {
                        MlxTextField(
                            value = t.brand,
                            onValueChange = { vm.updateBrand(i, it, t.monthlyTarget) },
                            placeholder = "Brand",
                            modifier = Modifier.weight(2f),
                        )
                        MlxTextField(
                            value = if (t.monthlyTarget == 0) "" else t.monthlyTarget.toString(),
                            onValueChange = {
                                vm.updateBrand(i, t.brand, it.toIntOrNull() ?: 0)
                            },
                            placeholder = "Target",
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f),
                        )
                        MlxIconButton(
                            icon = Icons.Filled.Close,
                            contentDescription = "Remove ${t.brand.ifBlank { "brand target" }}",
                            onClick = { vm.removeBrand(i) },
                            tint = Mlx.Text400,
                        )
                    }
                    Spacer(Modifier.height(MlxD.Space2))
                    if (t.brand.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(t.brand, style = MlxType.BodySmall, fontWeight = FontWeight.SemiBold, color = Mlx.Text900)
                            Text(
                                "${t.captured} / ${t.monthlyTarget} · ${t.percent}%",
                                style = MlxType.BodySmall,
                                color = Mlx.Text500,
                            )
                        }
                        ProgressTrack(
                            progress = t.percent / 100f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(MlxD.Space3))
                    }
                }
            }

            Spacer(Modifier.height(MlxD.Space4))
            vm.error?.let { MlxErrorLine(it, Modifier.padding(bottom = MlxD.Space2)) }
            if (vm.saved) {
                Text(
                    text = "Officer card saved.",
                    style = MlxType.BodySmall,
                    color = Mlx.Ok600,
                    modifier = Modifier.padding(bottom = MlxD.Space2),
                )
            }
            MlxButton(
                text = "Save officer card",
                onClick = vm::save,
                icon = Icons.Filled.Save,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(MlxD.Space3))
            OutlinedButton(
                onClick = vm::signOut,
                enabled = vm.pendingUploads == 0 && !vm.signOutBusy && !vm.purgeBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
            val pending = vm.pendingUploads
            if (pending == null) {
                Text("Checking pending uploads…", style = MlxType.BodySmall, color = Mlx.Text500)
            } else if (pending > 0) {
                Text("Sync in progress. Please wait for $pending pending scans to upload before signing out.",
                    style = MlxType.BodySmall, color = Mlx.Warn600)
                if (vm.syncTrouble) {
                    Text("A sync attempt failed or has repeatedly retried. Check your connection, or use Clear Trapped Local Data below to remove unsynced scans before signing out.",
                        style = MlxType.BodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text("If scans have no assigned company, use Assign & Sync on the Scan screen. You can also clear unsynced local data under Advanced.",
                    style = MlxType.Footnote, color = Mlx.Text500)
            }
            vm.signOutError?.let { Text(it, style = MlxType.BodySmall, color = MaterialTheme.colorScheme.error) }
        }

        MlxCard {
            Row(verticalAlignment = Alignment.Top) {
                Text("📶", style = MlxType.Body)
                Spacer(Modifier.width(MlxD.Space2))
                Text(
                    text = "Offline-first: scans cache on-device when the rural network drops, " +
                        "then sync on reconnect.",
                    style = MlxType.BodySmall,
                    color = Mlx.Text600,
                )
            }
        }

        if (vm.trappedCount > 0 || vm.purgeMessage != null) {
            MlxCard {
                Text("Advanced", style = MlxType.CardTitle, color = Mlx.Text900)
                if (vm.trappedCount > 0) {
                    Text("${vm.trappedCount} unsynced or legacy local audits across this device", style = MlxType.BodySmall, color = Mlx.Text500)
                    OutlinedButton(onClick = vm::preparePurge, enabled = !vm.purgeBusy && !vm.signOutBusy) {
                        Text("Clear Trapped Local Data")
                    }
                }
                vm.purgeMessage?.let { Text(it, style = MlxType.BodySmall, color = Mlx.Text600) }
            }
        }

        MlxCard(
            modifier = Modifier.clickable { onOpenHelp() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖", style = MlxType.Body)
                Spacer(Modifier.width(MlxD.Space3))
                Column(Modifier.weight(1f)) {
                    Text("Help & Guide", style = MlxType.CardTitle, color = Mlx.Text900)
                    Text(
                        "Scan guide, error escalation, BMDC reference",
                        style = MlxType.BodySmall,
                        color = Mlx.Text500,
                    )
                }
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.ChevronRight,
                    contentDescription = null,
                    tint = Mlx.Text300,
                )
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = MlxType.FieldLabel,
        color = Mlx.Text500,
        modifier = Modifier.padding(bottom = MlxD.Space1),
    )
}

/** Keeps a previously saved custom value visible without silently replacing it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    value: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String = "Select an option",
    display: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = if (value.isNotBlank() && value !in options) listOf(value) + options else options
    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = display(value), onValueChange = {}, readOnly = true, enabled = enabled,
            label = { Text(label) }, placeholder = { Text(placeholder) }, singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        this.ExposedDropdownMenu(
            expanded = expanded && enabled, onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp),
        ) {
            choices.forEach { item ->
                DropdownMenuItem(text = { Text(display(item)) }, onClick = {
                    onSelected(item)
                    expanded = false
                })
            }
        }
    }
}
