package com.simple.proxyconnect.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.simple.proxyconnect.model.ConnectionStatus
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Manages the VPN tunnel lifecycle, acts as bridge between ViewModel and VpnService.
 */
class TunnelManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    var statusListener: ((ConnectionStatus) -> Unit)? = null

    val status: ConnectionStatus
        get() = if (SimpleVpnService.isRunning.get()) ConnectionStatus.Connected
                else ConnectionStatus.Disconnected

    init {
        SimpleVpnService.statusCallback = { isConnected ->
            statusListener?.invoke(
                if (isConnected) ConnectionStatus.Connected
                else ConnectionStatus.Disconnected
            )
        }
    }

    /**
     * Check if VPN permission is granted. Returns null if already granted,
     * or an Intent to request permission.
     */
    fun prepareVpn(): Intent? {
        return VpnService.prepare(context)
    }

    /**
     * Start the VPN tunnel with the given node and routing mode.
     */
    fun startTunnel(node: ProxyNode, routingMode: RoutingMode) {
        statusListener?.invoke(ConnectionStatus.Connecting)

        val intent = Intent(context, SimpleVpnService::class.java).apply {
            action = SimpleVpnService.ACTION_START
            putExtra(SimpleVpnService.EXTRA_NODE_JSON, json.encodeToString(node))
            putExtra(SimpleVpnService.EXTRA_ROUTING_MODE, routingMode.name)
        }
        context.startForegroundService(intent)
    }

    /**
     * Stop the VPN tunnel.
     */
    fun stopTunnel() {
        statusListener?.invoke(ConnectionStatus.Disconnecting)

        val intent = Intent(context, SimpleVpnService::class.java).apply {
            action = SimpleVpnService.ACTION_STOP
        }
        context.startService(intent)
    }
}
