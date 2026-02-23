package com.simple.proxyconnect.ui.screen

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.ProxyProtocolType
import com.simple.proxyconnect.service.CryptoService
import com.simple.proxyconnect.ui.components.QRScannerView
import com.simple.proxyconnect.viewmodel.ProxyViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddNodeScreen(
    viewModel: ProxyViewModel,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Node") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SecondaryTabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Scan QR") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Manual") }
                )
            }

            when (selectedTab) {
                0 -> ScanTab(
                    viewModel = viewModel,
                    onDismiss = onDismiss,
                    onError = {
                        Toast.makeText(context, "Invalid URI format", Toast.LENGTH_SHORT).show()
                    }
                )
                1 -> ManualTab(
                    viewModel = viewModel,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun ScanTab(
    viewModel: ProxyViewModel,
    onDismiss: () -> Unit,
    onError: () -> Unit
) {
    var uriText by remember { mutableStateOf("") }
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // QR Scanner
        QRScannerView(
            onCodeScanned = { code ->
                if (viewModel.parseAndAddNode(code)) {
                    onDismiss()
                } else {
                    onError()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .clip(RoundedCornerShape(16.dp))
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Or paste a connection URI",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = uriText,
                onValueChange = { uriText = it },
                placeholder = { Text("simple://") },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (viewModel.parseAndAddNode(uriText)) {
                        onDismiss()
                    } else {
                        onError()
                    }
                },
                enabled = uriText.isNotBlank()
            ) {
                Text("Add")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                clipboardManager.getText()?.text?.let { uriText = it }
            }
        ) {
            Icon(
                Icons.Filled.ContentPaste,
                contentDescription = null,
                modifier = Modifier.padding(end = 4.dp)
            )
            Text("Paste from Clipboard")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ManualTab(
    viewModel: ProxyViewModel,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }
    var spKey by remember { mutableStateOf("") }

    val isFormValid = remember(host, port, spKey) {
        val hostValid = host.trim().isNotEmpty()
        val portValid = port.toIntOrNull()?.let { it in 1..65535 } ?: false
        val protoValid = spKey.length == 64 && CryptoService.hexToBytes(spKey) != null
        hostValid && portValid && protoValid
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Section: Server
        Text(
            "Server",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name (optional)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host / IP") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = port,
            onValueChange = { port = it },
            label = { Text("Port") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Section: Protocol
        Text(
            "Protocol",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = ProxyProtocolType.SimpleProtocol.displayName,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = spKey,
            onValueChange = { spKey = it },
            label = { Text("Pre-shared Key (64 hex chars)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                val portNum = port.toIntOrNull() ?: return@Button
                val nodeName = name.trim().ifEmpty { host }
                val node = ProxyNode(
                    name = nodeName,
                    host = host.trim(),
                    port = portNum,
                    protocolType = ProxyProtocolType.SimpleProtocol,
                    spKey = spKey
                )
                viewModel.addNode(node)
                onDismiss()
            },
            enabled = isFormValid,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Add Node")
        }
    }
}
