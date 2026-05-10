package com.simple.proxyconnect.service.singbox

import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun globalMode_finalIsProxy_andDnsFinalIsRemoteDns() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.Global))

        val route = config.getObject("route")
        assertEquals("proxy", route.getString("final"))

        // Global mode shouldn't declare per-domain/IP outbound overrides — anything
        // beyond the universal `action: sniff` rule belongs in BypassChina.
        val routeRules = route.getArray("rules")
        for (rule in routeRules) {
            val obj = rule.jsonObject
            // Only the `action` rule (sniff) is acceptable in Global mode.
            assertNull(
                "Global mode must not have outbound-override rules (got $obj)",
                obj["outbound"]
            )
        }

        val dns = config.getObject("dns")
        assertEquals("remote-dns", dns.getString("final"))
        // Global mode also doesn't need per-domain DNS rules.
        assertNull("Global mode should not have dns.rules", dns["rules"])
    }

    @Test
    fun bypassChinaMode_routesGfwToProxyAndChinaIpsToDirect() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.BypassChina))

        val route = config.getObject("route")
        assertEquals("direct", route.getString("final"))

        val routeRules = route.getArray("rules")
        // GFW domains → proxy via rule_set
        assertTrue("Expected gfw-domains → proxy rule", routeRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null &&
                    obj["rule_set"]!!.jsonArray.any { r -> r.jsonPrimitive.content == "gfw-domains" } &&
                    obj["outbound"]?.jsonPrimitive?.content == "proxy"
        })
        // China IPs → direct via rule_set
        assertTrue("Expected china-ips → direct rule", routeRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null &&
                    obj["rule_set"]!!.jsonArray.any { r -> r.jsonPrimitive.content == "china-ips" } &&
                    obj["outbound"]?.jsonPrimitive?.content == "direct"
        })
        // Private/loopback IPs should also be direct.
        assertTrue("Expected ip_is_private → direct rule", routeRules.any {
            val obj = it.jsonObject
            obj["ip_is_private"]?.jsonPrimitive?.boolean == true &&
                    obj["outbound"]?.jsonPrimitive?.content == "direct"
        })

        val dns = config.getObject("dns")
        // GFW domains use remote-dns
        val dnsRules = dns.getArray("rules")
        assertTrue("Expected gfw-domains DNS rule pointing at remote-dns", dnsRules.any {
            val obj = it.jsonObject
            obj["rule_set"] != null && obj["server"]?.jsonPrimitive?.content == "remote-dns"
        })
        // Final DNS is direct-dns
        assertEquals("direct-dns", dns.getString("final"))
    }

    /**
     * Regression: the previous config bundled a `dns-out` (type=dns) outbound
     * and a `protocol: dns` route rule pointing at it, plus a `block`
     * outbound. In sing-box ≥ 1.12 that combination caused DoH targets like
     * `dns.google:443` and `chrome.cloudflare-dns.com:443` to surface
     * `outbound/block[block]: operation not permitted`. The fix removes both
     * outbounds and the `protocol: dns` rule.
     */
    @Test
    fun config_doesNotDeclareBlockOutboundOrDnsOutbound() {
        for (mode in RoutingMode.values()) {
            val config = parseConfig(SingBoxConfigBuilder.build(node, mode))
            val outbounds = config.getArray("outbounds")
            for (out in outbounds) {
                val type = out.jsonObject["type"]?.jsonPrimitive?.content
                val tag = out.jsonObject["tag"]?.jsonPrimitive?.content
                assertFalse(
                    "block outbound must not exist (mode=$mode, tag=$tag)",
                    type == "block" || tag == "block"
                )
                assertFalse(
                    "dns-type outbound must not exist (mode=$mode, tag=$tag)",
                    type == "dns" || tag == "dns-out"
                )
            }

            val route = config.getObject("route")
            val routeRules = route.getArray("rules")
            for (rule in routeRules) {
                val obj = rule.jsonObject
                assertFalse(
                    "route rule must not match `protocol: dns` (mode=$mode)",
                    obj["protocol"]?.jsonPrimitive?.content == "dns"
                )
                val outbound = obj["outbound"]?.jsonPrimitive?.content
                assertFalse(
                    "no route rule should target dns-out (mode=$mode)",
                    outbound == "dns-out"
                )
                assertFalse(
                    "no route rule should target block (mode=$mode)",
                    outbound == "block"
                )
            }
        }
    }

    @Test
    fun config_singleMixedInboundOnly_andDoesNotUseLegacySniffFields() {
        for (mode in RoutingMode.values()) {
            val inbounds = parseConfig(SingBoxConfigBuilder.build(node, mode)).getArray("inbounds")
            assertEquals("Expected exactly one inbound (mixed) for mode=$mode", 1, inbounds.size)
            val first = inbounds.first().jsonObject
            assertEquals("mixed", first["type"]?.jsonPrimitive?.content)
            assertEquals("mixed-in", first["tag"]?.jsonPrimitive?.content)
            // sing-box 1.13 removed legacy inbound `sniff` / `sniff_override_destination`.
            // Sniffing is performed via a route rule action instead (see below).
            assertNull("sniff field must not appear on inbound for mode=$mode", first["sniff"])
            assertNull(
                "sniff_override_destination field must not appear on inbound for mode=$mode",
                first["sniff_override_destination"]
            )
        }
    }

    @Test
    fun route_includesSniffActionRule() {
        for (mode in RoutingMode.values()) {
            val rules = parseConfig(SingBoxConfigBuilder.build(node, mode))
                .getObject("route")
                .getArray("rules")
            assertTrue("Expected an action=sniff rule for mode=$mode", rules.any {
                it.jsonObject["action"]?.jsonPrimitive?.content == "sniff"
            })
        }
    }

    @Test
    fun dns_usesNew1_12ServerFormat() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.BypassChina))
        val servers = config.getObject("dns").getArray("servers")
        for (s in servers) {
            val obj = s.jsonObject
            // New format requires a `type` field (udp/tcp/tls/quic/...).
            assertTrue(
                "DNS server entry $obj must declare a `type` field",
                obj["type"] != null
            )
            // Legacy `address: "tcp://...."` should not be used; we use `server` + `type`.
            val addr = obj["server"]?.jsonPrimitive?.content
            assertTrue(
                "DNS server entry must declare a plain host in `server` (got: $addr)",
                addr != null && !addr.contains("://")
            )
        }
    }

    @Test
    fun config_proxyOutboundPointsAtSimpleProtocolBridge() {
        val config = parseConfig(SingBoxConfigBuilder.build(node, RoutingMode.Global))
        val proxy = config.getArray("outbounds").first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "proxy"
        }.jsonObject
        assertEquals("socks", proxy["type"]?.jsonPrimitive?.content)
        assertEquals("127.0.0.1", proxy["server"]?.jsonPrimitive?.content)
        assertEquals("16080", proxy["server_port"]?.jsonPrimitive?.content)
    }

    private fun parseConfig(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.getObject(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.getArray(key: String): JsonArray = this[key]!!.jsonArray

    private fun JsonObject.getString(key: String): String = this[key]!!.jsonPrimitive.content
}
