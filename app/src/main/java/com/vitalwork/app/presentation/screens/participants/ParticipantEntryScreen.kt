package com.vitalwork.app.presentation.screens.participants

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

private const val MIN_AGE = 18
private const val MAX_AGE = 80

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParticipantEntryScreen(
    onNavigateBack: () -> Unit,
    onSessionStarted: (Long) -> Unit,
    viewModel: ParticipantEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ParticipantEntryEvent.SessionStarted -> onSessionStarted(event.sessionId)
                is ParticipantEntryEvent.ActiveSessionDetected -> onSessionStarted(event.sessionId)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "New Participant",
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
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Header
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.padding(start = 12.dp)) {
                                Text(
                                    text = "New Participant",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Enter the anonymized code and basic demographics, " +
                                            "then start the session.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Participant code — auto-generated from the device prefix (Settings),
                        // read-only so it can't be edited into a colliding code.
                        OutlinedTextField(
                            value = uiState.participantCode,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Participant code") },
                            singleLine = true,
                            isError = uiState.codeError != null,
                            supportingText = uiState.codeError?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Age stepper
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Age (18 to 80 years old)",
                                style = MaterialTheme.typography.labelLarge
                            )
                            AgeStepper(
                                value = uiState.ageInput,
                                enabled = !uiState.isSubmitting,
                                isError = uiState.ageError != null,
                                onValueChange = viewModel::onAgeChange
                            )
                            uiState.ageError?.let {
                                Text(
                                    text = it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }

                        // Gender segmented buttons
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Gender",
                                style = MaterialTheme.typography.labelLarge
                            )
                            GenderSegmentedRow(
                                selected = uiState.gender,
                                enabled = !uiState.isSubmitting,
                                onSelected = viewModel::onGenderChange
                            )
                        }

                        uiState.submitError?.let {
                            Text(
                                text = it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Button(
                            onClick = viewModel::submit,
                            enabled = uiState.isInitialized && !uiState.isSubmitting,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (uiState.isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(text = "Start session")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AgeStepper(
    value: String,
    enabled: Boolean,
    isError: Boolean,
    onValueChange: (String) -> Unit
) {
    val current = value.toIntOrNull()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedIconButton(
            onClick = {
                current?.let { onValueChange((it - 1).coerceIn(MIN_AGE, MAX_AGE).toString()) }
            },
            enabled = enabled && current != null
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = "Decrease age"
            )
        }

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(
                    text = "–",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            singleLine = true,
            isError = isError,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = MaterialTheme.typography.titleLarge.copy(textAlign = TextAlign.Center),
            modifier = Modifier.width(96.dp)
        )

        OutlinedIconButton(
            onClick = {
                current?.let { onValueChange((it + 1).coerceIn(MIN_AGE, MAX_AGE).toString()) }
            },
            enabled = enabled && current != null
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Increase age"
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenderSegmentedRow(
    selected: GenderOption,
    enabled: Boolean,
    onSelected: (GenderOption) -> Unit
) {
    val options = GenderOption.entries
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelected(option) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
            ) {
                Text(text = option.shortLabel())
            }
        }
    }
}

private fun GenderOption.shortLabel(): String =
    if (this == GenderOption.NOT_SPECIFIED) "N/A" else label
