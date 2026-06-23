package com.coursemapper.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.coursemapper.ui.designsystem.CmEmptyState
import com.coursemapper.ui.designsystem.CmLoading
import com.coursemapper.ui.designsystem.CmTopBar
import com.coursemapper.ui.format.LocalDistanceFormatter
import com.coursemapper.ui.format.rememberDistanceUnit
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RoutesScreen(
    onBack: () -> Unit,
    onCourseClicked: (courseId: Long) -> Unit,
    onNetworkClicked: (networkId: Long) -> Unit,
    viewModel: RoutesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            CmTopBar(
                title   = "Courses",
                onBack  = onBack
            )
        }
    ) { padding ->
        if (state.isLoading) {
            CmLoading(Modifier.padding(padding))
            return@Scaffold
        }

        if (state.items.isEmpty()) {
            CmEmptyState(
                modifier = Modifier.padding(padding),
                icon     = Icons.Default.Route,
                title    = "No courses yet",
                body     = "Record or import a route to create a course."
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(state.items, key = { it.networkId }) { item ->
                if (item.variantCourses.isNotEmpty()) {
                    // Multi-distance network card
                    NetworkCard(
                        item             = item,
                        onNetworkClicked = { onNetworkClicked(item.networkId) },
                        onVariantClicked = { courseId -> if (courseId != null) onCourseClicked(courseId) }
                    )
                } else {
                    // Standalone / trivially-wrapped network - show course(s) directly
                    item.standaloneCourses.forEach { course ->
                        StandaloneCourseCard(
                            item     = course,
                            networkName = item.networkName,
                            updatedAt = item.updatedAt,
                            trunkKm  = item.trunkDistanceKm,
                            onClick  = { onCourseClicked(course.courseId) },
                            onNetworkClicked = { onNetworkClicked(item.networkId) }
                        )
                    }
                    // If no courses yet (route not yet composed to a course)
                    if (item.standaloneCourses.isEmpty()) {
                        DraftNetworkCard(
                            item             = item,
                            onNetworkClicked = { onNetworkClicked(item.networkId) }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkCard(
    item: NetworkDisplayItem,
    onNetworkClicked: () -> Unit,
    onVariantClicked: (courseId: Long?) -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier              = Modifier.fillMaxWidth().clickable(onClick = onNetworkClicked),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.networkName,
                        style    = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${item.variantCourses.size} distance(s) · Updated ${formatDate(item.updatedAt)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    Icons.Default.AccountTree,
                    contentDescription = "View network",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Variant chips
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.variantCourses.forEach { variant ->
                    VariantChip(variant = variant, onClick = { onVariantClicked(variant.courseId) })
                }
            }
        }
    }
}

@Composable
private fun VariantChip(variant: VariantCourseItem, onClick: () -> Unit) {
    val enabled = variant.isApproved && variant.courseId != null
    FilterChip(
        selected = false,
        onClick  = { if (enabled) onClick() },
        label    = {
            val formatter = LocalDistanceFormatter.current
            val unit by rememberDistanceUnit()
            Text(
                buildString {
                    append(variant.variantName)
                    variant.totalKm?.let { append(" · ${formatter.formatCompact(it * 1_000.0, unit)}") }
                },
                style = MaterialTheme.typography.labelMedium
            )
        },
        leadingIcon = if (variant.isApproved) {
            { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(14.dp)) }
        } else {
            { Icon(Icons.Default.Schedule, contentDescription = null, modifier = Modifier.size(14.dp)) }
        },
        enabled = enabled
    )
}

@Composable
private fun StandaloneCourseCard(
    item: StandaloneCourseItem,
    networkName: String,
    updatedAt: Long,
    trunkKm: Double?,
    onClick: () -> Unit,
    onNetworkClicked: () -> Unit
) {
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                networkName,
                style    = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                val formatter = LocalDistanceFormatter.current
                val unit by rememberDistanceUnit()
                val km = trunkKm ?: (item.totalKm)
                StatChip(Icons.Default.Straighten, formatter.formatCompact(km * 1_000.0, unit))
                StatChip(Icons.Default.Place, "${item.markerCount} markers")
            }
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Text(
                    "Updated ${formatDate(updatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick        = onNetworkClicked,
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Default.AccountTree,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Network", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun DraftNetworkCard(item: NetworkDisplayItem, onNetworkClicked: () -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onNetworkClicked)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(item.networkName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Route approved — open to compose a course.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

private fun formatDate(ms: Long): String =
    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
