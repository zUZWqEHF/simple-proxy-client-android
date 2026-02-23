package com.simple.proxyconnect.service

import android.util.Base64
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.ProxyProtocolType

/**
 * Parses simple:// URIs into ProxyNode objects.
 */
object NodeParser {

    fun parse(uri: String): ProxyNode? {
        val trimmed = uri.trim()
        return when {
            trimmed.lowercase().startsWith("simple://") -> parseSimpleProtocol(trimmed)
            else -> null
        }
    }

    private fun parseSimpleProtocol(uri: String): ProxyNode? {
        var remaining = uri.substring(9)

        var name = ""
        val hashIndex = remaining.lastIndexOf('#')
        if (hashIndex >= 0) {
            name = java.net.URLDecoder.decode(remaining.substring(hashIndex + 1), "UTF-8")
            remaining = remaining.substring(0, hashIndex)
        }

        val decoded = base64Decode(remaining) ?: return null
        // Format: port:hexkey:host
        val parts = decoded.split(":", limit = 3)
        if (parts.size != 3) return null

        val port = parts[0].toIntOrNull() ?: return null
        val keyHex = parts[1]
        val keyData = CryptoService.hexToBytes(keyHex)
        if (keyData == null || keyData.size != 32) return null

        val host = parts[2]
        if (name.isEmpty()) name = host

        return ProxyNode(
            name = name, host = host, port = port,
            protocolType = ProxyProtocolType.SimpleProtocol,
            spKey = keyHex
        )
    }

    /**
     * Generate a URI string for the given node.
     */
    fun generateURI(node: ProxyNode): String {
        val key = node.spKey ?: return ""
        val payload = "${node.port}:$key:${node.host}"
        val encoded = base64UrlEncode(payload.toByteArray(Charsets.UTF_8))
        val encodedName = java.net.URLEncoder.encode(node.name, "UTF-8")
        return "simple://${encoded}#$encodedName"
    }

    private fun base64Decode(input: String): String? {
        val base64 = input
            .replace('-', '+')
            .replace('_', '/')
            .let {
                val pad = (4 - it.length % 4) % 4
                it + "=".repeat(pad)
            }
        return try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun parseHostPort(hostPort: String): Pair<String, Int>? {
        // Handle IPv6: [host]:port
        if (hostPort.startsWith("[")) {
            val closeBracket = hostPort.indexOf(']')
            if (closeBracket < 0) return null
            val host = hostPort.substring(1, closeBracket)
            if (closeBracket + 1 >= hostPort.length || hostPort[closeBracket + 1] != ':') return null
            val port = hostPort.substring(closeBracket + 2).toIntOrNull() ?: return null
            return Pair(host, port)
        }

        val lastColon = hostPort.lastIndexOf(':')
        if (lastColon < 0) return null
        val host = hostPort.substring(0, lastColon)
        val port = hostPort.substring(lastColon + 1).toIntOrNull() ?: return null
        return Pair(host, port)
    }
}
