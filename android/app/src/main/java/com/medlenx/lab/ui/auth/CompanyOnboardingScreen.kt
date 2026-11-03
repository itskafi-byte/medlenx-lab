package com.medlenx.lab.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.local.CompanySelection
import com.medlenx.lab.data.local.ManufacturerAliasDao
import com.medlenx.lab.ui.shell.BrandLockup

@Composable
fun CompanyOnboardingScreen(dao: ManufacturerAliasDao, busy: Boolean, error: String?, onContinue: (String) -> Unit) {
    BackHandler(enabled = true) { }
    var companies by remember { mutableStateOf<List<String>>(emptyList()) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf("") }
    var listError by remember { mutableStateOf(false) }
    LaunchedEffect(dao) {
        try { companies = dao.canonicalCompanies() } catch (_: Exception) { listError = true }
    }
    val other = "Other (Manual Entry)"
    val company = if (selected == other) manual.trim() else selected.orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        BrandLockup(tileSize = 52.dp)
        Text("Welcome to MedLenX", style = MaterialTheme.typography.headlineMedium)
        Text("Select your company to continue.", style = MaterialTheme.typography.bodyLarge)
        Text("This selection sets your personal analytics. Online team access still requires administrator approval.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(search, { search = it }, label = { Text("Search companies") },
            singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !busy)
        if (listError) Text("Could not load the company list. You can enter a company manually.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxWidth().weight(1f).heightIn(min = 120.dp)) {
            items(companies.filter { it.contains(search.trim(), ignoreCase = true) } + other) { name ->
                Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = name }.padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RadioButton(selected = selected == name, onClick = { selected = name }, enabled = !busy)
                    Text(name, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        if (selected == other) OutlinedTextField(manual, { if (it.length <= 500) manual = it },
            label = { Text("Company name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Text("An internet connection is required to check your cloud profile before continuing.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onContinue(company) }, enabled = !busy && CompanySelection.isAssigned(company), modifier = Modifier.fillMaxWidth()) {
            if (busy) CircularProgressIndicator() else Text("Continue")
        }
    }
}
