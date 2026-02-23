package com.simple.proxyconnect.service

import android.content.Context
import android.content.SharedPreferences
import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists proxy nodes and settings to SharedPreferences.
 */
class NodeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("simple_proxy_prefs", Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val NODES_KEY = "saved_nodes"
        private const val SELECTED_KEY = "selected_node_id"
        private const val ROUTING_KEY = "routing_mode"
    }

    fun loadNodes(): List<ProxyNode> {
        val data = prefs.getString(NODES_KEY, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<ProxyNode>>(data)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveNodes(nodes: List<ProxyNode>) {
        prefs.edit().putString(NODES_KEY, json.encodeToString(nodes)).apply()
    }

    fun loadSelectedNodeId(): String? {
        return prefs.getString(SELECTED_KEY, null)
    }

    fun saveSelectedNodeId(id: String?) {
        prefs.edit().putString(SELECTED_KEY, id).apply()
    }

    fun loadRoutingMode(): RoutingMode {
        val raw = prefs.getString(ROUTING_KEY, null) ?: return RoutingMode.Global
        return try {
            RoutingMode.valueOf(raw)
        } catch (e: Exception) {
            RoutingMode.Global
        }
    }

    fun saveRoutingMode(mode: RoutingMode) {
        prefs.edit().putString(ROUTING_KEY, mode.name).apply()
    }
}
