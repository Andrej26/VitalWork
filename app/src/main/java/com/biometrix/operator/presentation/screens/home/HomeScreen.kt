package com.biometrix.operator.presentation.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.biometrix.operator.presentation.components.ConnectionStatusBadge
import com.biometrix.operator.presentation.screens.home.components.PrimaryActionButton
import com.biometrix.operator.presentation.screens.home.components.SecondaryNavRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTutorial: () -> Unit,
    onNavigateToSensors: () -> Unit,
    onNavigateToVrControl: () -> Unit,
    onNavigateToSessions: () -> Unit,
    onNavigateToParticipantEntry: () -> Unit,
    onNavigateToSessionActive: (Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") onNavigateToSessionReview: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val vrConnectionState by viewModel.vrConnectionState.collectAsState()
    val activeSession by viewModel.activeSession.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shouldAutoShowTutorial by viewModel.shouldAutoShowTutorial.collectAsState()

    LaunchedEffect(shouldAutoShowTutorial) {
        if (shouldAutoShowTutorial) {
            viewModel.onTutorialAutoShown()
            onNavigateToTutorial()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "BioMetrix Operator",
                        fontWeight = FontWeight.SemiBold
                    )
                },
                actions = {
                    ConnectionStatusBadge(
                        state = vrConnectionState,
                        label = "VR",
                        modifier = Modifier.padding(end = 16.dp)
                    )
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
            contentAlignment = Alignment.Center
        ) {
            val currentActive = activeSession

            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                PrimaryActionButton(
                    title = if (currentActive != null) "Resume Active Session" else "Start New Session",
                    subtitle = currentActive?.sessionCode,
                    enabled = !isStarting,
                    onClick = {
                        if (currentActive != null) {
                            onNavigateToSessionActive(currentActive.id)
                        } else {
                            viewModel.beginSession(
                                onResumeActive = onNavigateToSessionActive,
                                onStartNewParticipantFlow = onNavigateToParticipantEntry
                            )
                        }
                    }
                )

                PrimaryActionButton(
                    title = "Completed Sessions",
                    subtitle = "Browse and export past sessions",
                    onClick = onNavigateToSessions,
                    icon = Icons.Default.Folder,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )

                HorizontalDivider()

                SecondaryNavRow(
                    onSensors = onNavigateToSensors,
                    onVrControl = onNavigateToVrControl,
                    onTutorial = onNavigateToTutorial
                )
            }
        }
    }
}
