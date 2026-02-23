package com.simple.proxyconnect.viewmodel

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.simple.proxyconnect.model.ConnectionStatus
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import com.simple.proxyconnect.service.NodeParser
import com.simple.proxyconnect.service.NodeStore
import com.simple.proxyconnect.service.TunnelManager

class ProxyViewModel(application: Application) : AndroidViewModel(application) {

    private val store = NodeStore(application)
    val tunnelManager = TunnelManager(application)

    var nodes = mutableStateListOf<ProxyNode>()
        private set

    var selectedNodeId by mutableStateOf<String?>(null)
        private set

    var routingMode by mutableStateOf(RoutingMode.Global)
        private set

    var connectionStatus by mutableStateOf(ConnectionStatus.Disconnected)
        private set

    var showError by mutableStateOf(false)
    var errorMessage by mutableStateOf("")

    val selectedNode: ProxyNode?
        get() = nodes.find { it.id == selectedNodeId }

    // Intent for requesting VPN permission. Non-null means we need to ask.
    var vpnPermissionIntent by mutableStateOf<Intent?>(null)
        private set

    init {
        nodes.addAll(store.loadNodes())
        selectedNodeId = store.loadSelectedNodeId() ?: nodes.firstOrNull()?.id
        routingMode = store.loadRoutingMode()
        connectionStatus = tunnelManager.status

        tunnelManager.statusListener = { status ->
            connectionStatus = status
        }
    }

    fun addNode(node: ProxyNode) {
        val existingIndex = nodes.indexOfFirst { it.host == node.host && it.port == node.port }
        if (existingIndex >= 0) {
            val oldId = nodes[existingIndex].id
            nodes[existingIndex] = node.copy(id = oldId)
            if (selectedNodeId == null) selectedNodeId = oldId
        } else {
            nodes.add(node)
            if (selectedNodeId == null) selectedNodeId = node.id
        }
        save()
    }

    fun deleteNode(node: ProxyNode) {
        nodes.remove(node)
        if (selectedNodeId == node.id) selectedNodeId = nodes.firstOrNull()?.id
        save()
    }

    fun selectNode(node: ProxyNode) {
        selectedNodeId = node.id
        store.saveSelectedNodeId(selectedNodeId)
    }

    fun updateRoutingMode(mode: RoutingMode) {
        if (routingMode == mode) return
        routingMode = mode
        store.saveRoutingMode(mode)
        if (connectionStatus == ConnectionStatus.Connected) {
            val node = selectedNode ?: return
            tunnelManager.startTunnel(node, routingMode)
        }
    }

    fun toggleConnection() {
        when (connectionStatus) {
            ConnectionStatus.Disconnected -> connect()
            ConnectionStatus.Connected -> disconnect()
            else -> {} // do nothing during transition states
        }
    }

    fun parseAndAddNode(uri: String): Boolean {
        val node = NodeParser.parse(uri) ?: return false
        addNode(node)
        return true
    }

    /**
     * Called after VPN permission is granted by user.
     */
    fun onVpnPermissionGranted() {
        vpnPermissionIntent = null
        val node = selectedNode ?: return
        tunnelManager.startTunnel(node, routingMode)
    }

    fun onVpnPermissionDenied() {
        vpnPermissionIntent = null
        showErrorWith("VPN permission denied")
    }

    private fun connect() {
        val node = selectedNode
        if (node == null) {
            showErrorWith("Please add and select a node first")
            return
        }

        // Check VPN permission
        val prepareIntent = tunnelManager.prepareVpn()
        if (prepareIntent != null) {
            vpnPermissionIntent = prepareIntent
            return
        }

        tunnelManager.startTunnel(node, routingMode)
    }

    private fun disconnect() {
        tunnelManager.stopTunnel()
    }

    private fun save() {
        store.saveNodes(nodes.toList())
        store.saveSelectedNodeId(selectedNodeId)
    }

    private fun showErrorWith(message: String) {
        errorMessage = message
        showError = true
    }
}
