package com.simple.proxyconnect.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.simple.proxyconnect.model.ConnectionStatus
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import com.simple.proxyconnect.ui.components.ConnectionButton
import com.simple.proxyconnect.ui.components.NodeRowView
import com.simple.proxyconnect.ui.components.QRShareDialog
import com.simple.proxyconnect.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: ProxyViewModel,
    onAddNode: () -> Unit
) {
    val statusColor = when (viewModel.connectionStatus) {
        ConnectionStatus.Disconnected -> Color.Gray
        ConnectionStatus.Connecting, ConnectionStatus.Disconnecting -> Color(0xFFFF9800)
        ConnectionStatus.Connected -> Color(0xFF4CAF50)
    }

    var qrShareNode by remember { mutableStateOf<ProxyNode?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("simple proxy", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = onAddNode) {
                        Icon(
                            imageVector = Icons.Filled.AddCircle,
                            contentDescription = "Add Node",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Header section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    statusColor.copy(alpha = 0.05f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Status text
                    Text(
                        text = viewModel.connectionStatus.displayText,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = statusColor
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Selected node name
                    val nodeName = viewModel.selectedNode?.name ?: "No node selected"
                    Text(
                        text = nodeName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (viewModel.selectedNode != null) 0.6f else 0.3f
                        )
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Connection button
                    ConnectionButton(
                        status = viewModel.connectionStatus,
                        onClick = { viewModel.toggleConnection() }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Routing mode selector
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RoutingMode.entries.forEach { mode ->
                            FilterChip(
                                selected = viewModel.routingMode == mode,
                                onClick = { viewModel.updateRoutingMode(mode) },
                                label = { Text(mode.displayName) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            )
                        }
                    }
                }
            }

            // Nodes header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "NODES",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (viewModel.nodes.isNotEmpty()) {
                        Text(
                            text = "${viewModel.nodes.size}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Nodes list or empty state
            if (viewModel.nodes.isEmpty()) {
                item {
                    EmptyNodesState(onAddNode = onAddNode)
                }
            } else {
                items(viewModel.nodes, key = { it.id }) { node ->
                    NodeRowView(
                        node = node,
                        isSelected = node.id == viewModel.selectedNodeId,
                        onSelect = { viewModel.selectNode(node) },
                        onDelete = { viewModel.deleteNode(node) },
                        onShareQR = { qrShareNode = node },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Error dialog
    if (viewModel.showError) {
        AlertDialog(
            onDismissRequest = { viewModel.showError = false },
            title = { Text("Error") },
            text = { Text(viewModel.errorMessage) },
            confirmButton = {
                TextButton(onClick = { viewModel.showError = false }) {
                    Text("OK")
                }
            }
        )
    }

    // QR share dialog
    qrShareNode?.let { node ->
        QRShareDialog(
            node = node,
            onDismiss = { qrShareNode = null }
        )
    }
}

@Composable
private fun EmptyNodesState(onAddNode: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerHigh,
                RoundedCornerShape(16.dp)
            )
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Dns,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "No nodes configured",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onAddNode) {
            Text("Add Node")
        }
    }
}
