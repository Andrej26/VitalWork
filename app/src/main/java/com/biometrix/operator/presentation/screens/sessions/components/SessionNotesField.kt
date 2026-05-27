package com.biometrix.operator.presentation.screens.sessions.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class NotesSaveStatus { Idle, Saving, Saved }

@Composable
fun SessionNotesField(
    notes: String,
    onNotesChange: (String) -> Unit,
    readOnly: Boolean = false,
    initiallyExpanded: Boolean = false,
    saveStatus: NotesSaveStatus = NotesSaveStatus.Idle,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(initiallyExpanded || notes.isNotBlank()) }
    var isEditing by remember { mutableStateOf(!readOnly) }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Test Notes",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )

                // Save status indicator
                AnimatedVisibility(
                    visible = saveStatus != NotesSaveStatus.Idle,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        when (saveStatus) {
                            NotesSaveStatus.Saving -> {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    strokeWidth = 1.5.dp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Saving...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            NotesSaveStatus.Saved -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Saved",
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFF4CAF50)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Saved",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            NotesSaveStatus.Idle -> { /* hidden by AnimatedVisibility */ }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                if (readOnly) {
                    IconButton(onClick = {
                        isEditing = !isEditing
                        if (isEditing) {
                            isExpanded = true
                        } else if (notes.isBlank()) {
                            isExpanded = false
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = if (isEditing) "Done editing" else "Edit notes"
                        )
                    }
                }

                if (!readOnly) {
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (isExpanded) "Collapse" else "Expand"
                        )
                    }
                }
            }

            // Expandable content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))

                    if (readOnly && !isEditing) {
                        if (notes.isBlank()) {
                            Text(
                                text = "No notes for this test",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Text(
                                text = notes,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = notes,
                            onValueChange = onNotesChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text("Add observations or notes...")
                            },
                            minLines = 2,
                            maxLines = 6
                        )
                    }
                }
            }

            // Collapsed preview
            if (!isExpanded && notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = notes.take(80) + if (notes.length > 80) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}
