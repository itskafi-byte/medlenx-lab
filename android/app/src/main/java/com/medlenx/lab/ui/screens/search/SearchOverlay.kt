package com.medlenx.lab.ui.screens.search

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.MedexEntity
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Global medicine search, searching the bundled 25k-row MedEx catalogue on device.
 *
 * The export hardcodes six sample brands; this queries `medex_products` through
 * [com.medlenx.lab.data.local.MedexDao.search]. Queries are debounced so typing a
 * brand name does not fire a LIKE scan per keystroke.
 */
class SearchViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private var job: Job? = null

    var results by mutableStateOf<List<MedexEntity>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set
    var searched by mutableStateOf(false)
        private set

    fun search(query: String) {
        job?.cancel()
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            searching = false
            searched = false
            return
        }
        searching = true
        job = viewModelScope.launch {
            delay(200)
            results = app.graph.database.medexDao().search(q = q, limit = 24)
            searching = false
            searched = true
        }
    }
}

class SearchViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SearchViewModel(application) as T
}

/** `SearchOverlay` in `App.tsx:321`. */
@Composable
fun SearchOverlay(
    vm: SearchViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Mlx.Text900.copy(alpha = 0.35f))
            .clickable(onClick = onClose),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = MlxD.ScreenMargin, vertical = MlxD.Space3)
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Mlx.Surface)
                .border(BorderStroke(1.dp, Mlx.Brand200), RoundedCornerShape(12.dp))
                // Consumes taps on the panel so they do not fall through to the scrim
                // and close the overlay. `clickable(enabled = false)` would not
                // consume them at all, so the results list would dismiss on touch.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .verticalScroll(rememberScrollState()),
        ) {
            when {
                vm.searching -> Text(
                    text = "Searching the catalogue…",
                    style = MlxType.BodySmall,
                    color = Mlx.Text500,
                    modifier = Modifier.padding(12.dp),
                )
                vm.results.isEmpty() && vm.searched -> MlxEmptyState(
                    message = "No medicine matched. Try a generic name or the manufacturer.",
                    icon = Icons.Filled.Medication,
                    modifier = Modifier.padding(12.dp),
                )
                vm.results.isEmpty() -> Text(
                    text = "Search 25,105 bundled brands, generics and manufacturers.",
                    style = MlxType.BodySmall,
                    color = Mlx.Text500,
                    modifier = Modifier.padding(12.dp),
                )
                else -> vm.results.forEachIndexed { i, r ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onClose() }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Mlx.Screen)
                                .border(BorderStroke(1.dp, Mlx.Brand200), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Medication,
                                contentDescription = null,
                                tint = Mlx.Text400,
                                modifier = Modifier.size(MlxD.IconSmall),
                            )
                        }
                        Spacer(Modifier.width(MlxD.Space3))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = r.brandName,
                                style = MlxType.BodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Mlx.Text900,
                            )
                            Text(
                                text = listOf(
                                    listOf(r.type, r.strength).filter { it.isNotBlank() }
                                        .joinToString(" "),
                                    r.company,
                                ).filter { it.isNotBlank() }.joinToString(" • "),
                                style = MlxType.Footnote,
                                color = Mlx.Text500,
                                maxLines = 1,
                            )
                        }
                        if (r.type.isNotBlank()) {
                            StatusPill(text = r.type, tone = PillTone.Blue)
                        }
                    }
                    if (i < vm.results.lastIndex) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .height(1.dp)
                                .background(Mlx.Brand100),
                        )
                    }
                }
            }
        }
    }
}
