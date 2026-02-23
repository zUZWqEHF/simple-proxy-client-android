package com.simple.proxyconnect.model

import kotlinx.serialization.Serializable

@Serializable
enum class ProxyProtocolType(val displayName: String, val shortName: String) {
    SimpleProtocol("SimpleProtocol", "SP");
}

@Serializable
enum class RoutingMode(val displayName: String) {
    Global("Global"),
    BypassChina("Bypass CN");
}

enum class ConnectionStatus(val displayText: String) {
    Disconnected("Not Connected"),
    Connecting("Connecting…"),
    Connected("Connected"),
    Disconnecting("Disconnecting…");

    val isActive: Boolean
        get() = this == Connected || this == Connecting
}

@Serializable
data class ProxyNode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int,
    val protocolType: ProxyProtocolType = ProxyProtocolType.SimpleProtocol,
    val spKey: String? = null
) {
    val subtitle: String
        get() = "${protocolType.shortName} · $host:$port"
}
