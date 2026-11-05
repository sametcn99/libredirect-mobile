@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.libredirect.mobile.ui.services

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.libredirect.mobile.R
import dev.libredirect.mobile.ui.MainUiState
import dev.libredirect.mobile.ui.ServiceUiState
import dev.libredirect.mobile.ui.home.ServiceSort
import dev.libredirect.mobile.ui.home.ServiceSorter

private enum class ServiceFilter(
    @StringRes val labelRes: Int,
) {
    ALL(R.string.services_filter_all),
    ENABLED(R.string.services_filter_enabled),
    DISABLED(R.string.services_filter_disabled),
}

@Composable
fun ServicesScreen(
    state: MainUiState,
    onServiceClick: (String) -> Unit,
    onServiceEnabledChange: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var selectedFilter by rememberSaveable { mutableStateOf(ServiceFilter.ALL) }
    var selectedSort by rememberSaveable { mutableStateOf(ServiceSort.POPULARITY) }

    val sortedServices = ServiceSorter.sort(state.services, selectedSort)
    val filteredServices =
        sortedServices.filter { service ->
            val matchesSearch =
                searchQuery.isBlank() ||
                    service.name.contains(searchQuery, ignoreCase = true) ||
                    service.routeId.contains(searchQuery, ignoreCase = true)
            val matchesFilter =
                when (selectedFilter) {
                    ServiceFilter.ALL -> true
                    ServiceFilter.ENABLED -> service.enabled
                    ServiceFilter.DISABLED -> !service.enabled
                }
            matchesSearch && matchesFilter
        }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.services_title), fontWeight = FontWeight.Bold) },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                placeholder = {
                    // One line even for long translations: a wrapping
                    // placeholder grows the whole search field to two rows.
                    Text(
                        text = stringResource(R.string.services_search_placeholder),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.action_clear))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                        unfocusedBorderColor = Color.Transparent,
                    ),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(ServiceFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = { Text(stringResource(filter.labelRes)) },
                    )
                }

                item {
                    val sortLabelRes =
                        if (selectedSort == ServiceSort.NAME_ASC) {
                            R.string.services_sort_name_asc
                        } else {
                            R.string.services_sort_popularity
                        }
                    FilterChip(
                        selected = selectedSort == ServiceSort.NAME_ASC,
                        onClick = {
                            selectedSort =
                                if (selectedSort == ServiceSort.NAME_ASC) {
                                    ServiceSort.POPULARITY
                                } else {
                                    ServiceSort.NAME_ASC
                                }
                        },
                        label = { Text(stringResource(sortLabelRes)) },
                    )
                }
            }

            Text(
                text = stringResource(R.string.services_count_fmt, filteredServices.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().weight(1f),
            ) {
                items(filteredServices, key = ServiceUiState::routeId) { service ->
                    ServiceCard(
                        service = service,
                        onClick = { onServiceClick(service.routeId) },
                        onEnabledChange = { enabled -> onServiceEnabledChange(service.routeId, enabled) },
                    )
                }

                item {
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
fun ServiceCard(
    service: ServiceUiState,
    onClick: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
) {
    val selectedFrontendName =
        service.frontends.find { it.id == service.selectedFrontendId }?.name ?: service.selectedFrontendId
    val initialLetter = service.name.firstOrNull()?.uppercaseChar()?.toString() ?: "S"

    ElevatedCard(
        shape = RoundedCornerShape(16.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
            ) {
                Text(
                    text = initialLetter,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = service.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    ) {
                        Text(
                            text = selectedFrontendName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                    val statusTextRes =
                        if (service.enabled) R.string.service_status_active else R.string.service_status_disabled
                    Text(
                        text = stringResource(statusTextRes),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (service.enabled) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                    )
                }
            }

            Switch(
                checked = service.enabled,
                onCheckedChange = onEnabledChange,
            )

            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.action_detail),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
