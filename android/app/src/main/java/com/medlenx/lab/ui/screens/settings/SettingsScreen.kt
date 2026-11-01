package com.medlenx.lab.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    modifier: Modifier = Modifier,
    onOpenHelp: () -> Unit = {},
) {
    vm.purgeConfirmationCount?.let { count ->
        AlertDialog(
            onDismissRequest = vm::cancelPurge,
            title = { Text("Clear Trapped Local Data?") },
            text = { Text("Permanently delete $count local audits and their photos where safe? This includes unassigned legacy records from this device and rejected records for your signed-in account. Shared photos may be retained. Cloud records are not deleted. This cannot be undone.") },
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
                Text("If scans have no assigned company, use Assign & Sync on the Scan screen. For an old company assignment, contact your administrator.",
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
                    Text("${vm.trappedCount} trapped local audits", style = MlxType.BodySmall, color = Mlx.Text500)
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
