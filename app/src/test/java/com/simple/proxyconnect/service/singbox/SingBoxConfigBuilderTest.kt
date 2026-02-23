package com.simple.proxyconnect.service.singbox

import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigBuilderTest {

    private val json = Json

    private val node = ProxyNode(
        name = "test",
        host = "example.com",
        port = 23333,
        spKey = "test-key"
    )

    @Test
    fun globalMode_generatesProxyFinalAndRemoteDnsRule() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.Global))

        val route = config.getObject("route")
        assertEquals("proxy", route.getString("final"))

        // Global mode: DNS protocol → dns-out rule exists
        val routeRules = route.getArray("rules")
        assertTrue(routeRules.any { it.jsonObject["protocol"]?.jsonPrimitive?.content == "dns" })

        val dnsRules = config.getObject("dns").getArray("rules")
        assertEquals("remote-dns", dnsRules.first().jsonObject["server"]?.jsonPrimitive?.content)
    }

    @Test
    fun bypassChinaMode_generatesDirectFinalAndRuleSetRules() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.BypassChina))

        val route = config.getObject("route")
        assertEquals("direct", route.getString("final"))

        val routeRules = route.getArray("rules")
        // GFW domains → proxy via rule_set
        assertTrue(routeRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null &&
                    obj["rule_set"]!!.jsonArray.any { r -> r.jsonPrimitive.content == "gfw-domains" } &&
                    obj["outbound"]?.jsonPrimitive?.content == "proxy"
        })
        // China IPs → direct via rule_set
        assertTrue(routeRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null &&
                    obj["rule_set"]!!.jsonArray.any { r -> r.jsonPrimitive.content == "china-ips" } &&
                    obj["outbound"]?.jsonPrimitive?.content == "direct"
        })

        val dns = config.getObject("dns")
        // GFW domains use remote-dns
        val dnsRules = dns.getArray("rules")
        assertTrue(dnsRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null && obj["server"]?.jsonPrimitive?.content == "remote-dns"
        })
        // Final DNS is direct-dns
        assertEquals("direct-dns", dns.getString("final"))
    }

    private fun parseConfig(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.getObject(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.getArray(key: String): JsonArray = this[key]!!.jsonArray

    private fun JsonObject.getString(key: String): String = this[key]!!.jsonPrimitive.content
}
