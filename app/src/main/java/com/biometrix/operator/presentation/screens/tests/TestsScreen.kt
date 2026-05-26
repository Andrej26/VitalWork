package com.biometrix.operator.presentation.screens.tests

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.data.db.TestEntity
import com.biometrix.operator.data.db.TestStatus
import com.biometrix.operator.presentation.screens.tests.components.ActiveTestBanner
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestsScreen(
    onNavigateBack: () -> Unit,
    onOpenTest: (testId: Long) -> Unit,
    onOpenActiveTest: (testId: Long) -> Unit,
    viewModel: TestsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showActiveTestDialog by remember { mutableStateOf(false) }
    var showEndAndStartNewConfirmation by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Tests",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (uiState.activeTest != null) {
                        showActiveTestDialog = true
                    } else {
                        viewModel.createNewTest { testId ->
                            onOpenActiveTest(testId)
                        }
                    }
                },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null
                    )
                },
                text = { Text("New Test") }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Active test banner
            uiState.activeTest?.let { activeTest ->
                ActiveTestBanner(
                    testNumber = activeTest.testNumber,
                    duration = uiState.activeTestDuration,
                    heartRate = uiState.activeTestHeartRate,
                    isRecording = uiState.isRecording,
                    onResume = { onOpenActiveTest(activeTest.id) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Filter chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedFilter == TestFilter.ALL,
                    onClick = { viewModel.setFilter(TestFilter.ALL) },
                    label = { Text("All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                FilterChip(
                    selected = uiState.selectedFilter == TestFilter.ACTIVE,
                    onClick = { viewModel.setFilter(TestFilter.ACTIVE) },
                    label = { Text("Active") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )

                FilterChip(
                    selected = uiState.selectedFilter == TestFilter.COMPLETED,
                    onClick = { viewModel.setFilter(TestFilter.COMPLETED) },
                    label = { Text("Completed") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                )

                FilterChip(
                    selected = uiState.selectedFilter == TestFilter.EXPORTED,
                    onClick = { viewModel.setFilter(TestFilter.EXPORTED) },
                    label = { Text("Exported") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                )

            }

            if (uiState.tests.isEmpty() && !uiState.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (uiState.selectedFilter == TestFilter.ALL)
                                "No tests yet"
                            else
                                "No ${uiState.selectedFilter.name.lowercase()} tests",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (uiState.selectedFilter == TestFilter.ALL)
                                "Tap + to start your first therapy test"
                            else
                                "Try a different filter",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Text(
                            text = "${uiState.tests.size} test${if (uiState.tests.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    items(uiState.tests, key = { it.id }) { test ->
                        TestCard(
                            test = test,
                            onClick = {
                                if (test.status == TestStatus.ACTIVE) {
                                    onOpenActiveTest(test.id)
                                } else {
                                    onOpenTest(test.id)
                                }
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(80.dp))
                    }
                }
            }
        }
    }

    // Dialog shown when user tries to create a new test while one is active
    if (showActiveTestDialog) {
        val activeTest = uiState.activeTest
        AlertDialog(
            onDismissRequest = { showActiveTestDialog = false },
            title = { Text("Test in Progress") },
            text = {
                Text(
                    if (activeTest != null)
                        "Test #${activeTest.testNumber} is currently running."
                    else
                        "A test is currently running."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showActiveTestDialog = false
                    showEndAndStartNewConfirmation = true
                }) {
                    Text("End & Start New")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showActiveTestDialog = false
                    activeTest?.let { onOpenActiveTest(it.id) }
                }) {
                    Text("Continue Test")
                }
            }
        )
    }

    // Confirmation dialog for ending active test and starting a new one
    if (showEndAndStartNewConfirmation) {
        val activeTest = uiState.activeTest
        AlertDialog(
            onDismissRequest = { showEndAndStartNewConfirmation = false },
            title = { Text("End current test?") },
            //text = { Text("This will end the current test, save its data, and start a new one.") },
            text = { Text("This will end the current test and start a new one.") },
            confirmButton = {
                TextButton(onClick = {
                    showEndAndStartNewConfirmation = false
                    if (activeTest != null) {
                        viewModel.endAndStartNew(activeTest.id) { newTestId ->
                            onOpenActiveTest(newTestId)
                        }
                    }
                }) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEndAndStartNewConfirmation = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun TestCard(
    test: TestEntity,
    onClick: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, yyyy hh:mm a", Locale.getDefault())

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Test #${test.testNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    TestStatusBadge(status = test.status)
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = dateFormat.format(Date(test.createdAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (test.durationMs > 0) {
                        Text(
                            text = formatDuration(test.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (test.totalHeartRateSampleCount > 0) {
                        Text(
                            text = "HR: ${test.totalHeartRateSampleCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (test.totalRespirationSampleCount > 0) {
                        Text(
                            text = "Resp: ${test.totalRespirationSampleCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

            }

            Spacer(modifier = Modifier.width(8.dp))

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TestStatusBadge(status: TestStatus) {
    val (backgroundColor, textColor, text) = when (status) {
        TestStatus.ACTIVE -> Triple(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
            "Active"
        )
        TestStatus.COMPLETED -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            "Completed"
        )
        TestStatus.EXPORTED -> Triple(
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
            "Exported"
        )
    }

    Surface(
        color = backgroundColor,
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
