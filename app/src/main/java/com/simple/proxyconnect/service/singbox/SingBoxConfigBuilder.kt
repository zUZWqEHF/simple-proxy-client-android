package com.simple.proxyconnect.service.singbox

import com.simple.proxyconnect.model.ProxyNode
import com.simple.proxyconnect.model.RoutingMode
import com.simple.proxyconnect.service.ChinaIpRanges
import com.simple.proxyconnect.service.GFWListRules
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

object SingBoxConfigBuilder {

    /**
     * Generate sing-box JSON config.
     *
     * In **Global** mode, all traffic goes through the proxy outbound.
     *
     * In **BypassChina** mode, sing-box itself handles routing:
     *   - Known GFW domains → proxy (via remote-dns)
     *   - Everything else → direct (via direct-dns)
     * The VPN service excludes its own app from VPN routes via
     * addDisallowedApplication, so sing-box's direct outbound does NOT
     * loop through TUN.
     */
    fun build(
        node: ProxyNode,
        routingMode: RoutingMode,
        localMixedPort: Int = 2080,
        localDnsPort: Int = 6450,
        localSimpleBridgePort: Int = 16080,
        workDir: File? = null,
    ): String {
        val isBypass = (routingMode == RoutingMode.BypassChina)

        // Generate local rule-set files for BypassChina mode
        if (isBypass && workDir != null) {
            writeGfwRuleSet(workDir)
            writeChinaIpRuleSet(workDir)
        }

        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", "warn")
                put("timestamp", true)
            }

            // ── DNS ──
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "remote-dns")
                        put("address", "tcp://8.8.8.8")
                        put("detour", "proxy")
                    })
                    add(buildJsonObject {
                        put("tag", "direct-dns")
                        put("address", "223.5.5.5")
                        put("detour", "direct")
                    })
                }

                putJsonArray("rules") {
                    if (isBypass) {
                        // GFW domains use remote DNS (through proxy to avoid poisoning)
                        add(buildJsonObject {
                            putJsonArray("rule_set") { add(JsonPrimitive("gfw-domains")) }
                            put("server", "remote-dns")
                        })
                    } else {
                        // Global: all DNS through proxy
                        add(buildJsonObject {
                            put("server", "remote-dns")
                        })
                    }
                }

                put("strategy", "prefer_ipv4")
                put("final", if (isBypass) "direct-dns" else "remote-dns")
            }

            // ── Inbounds ──
            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", localMixedPort)
                    put("sniff", true)
                    put("sniff_override_destination", false)
                })
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "dns-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", localDnsPort)
                })
            }

            // ── Outbounds ──
            putJsonArray("outbounds") {
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "proxy")
                    put("server", "127.0.0.1")
                    put("server_port", localSimpleBridgePort)
                })
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
                add(buildJsonObject {
                    put("type", "block")
                    put("tag", "block")
                })
                add(buildJsonObject {
                    put("type", "dns")
                    put("tag", "dns-out")
                })
            }

            // ── Route ──
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("outbound", "dns-out")
                    })
                    if (isBypass) {
                        // GFW domains → proxy
                        add(buildJsonObject {
                            putJsonArray("rule_set") { add(JsonPrimitive("gfw-domains")) }
                            put("outbound", "proxy")
                        })
                        // Known China IPs → direct
                        add(buildJsonObject {
                            putJsonArray("rule_set") { add(JsonPrimitive("china-ips")) }
                            put("outbound", "direct")
                        })
                    }
                }

                if (isBypass && workDir != null) {
                    putJsonArray("rule_set") {
                        add(buildJsonObject {
                            put("tag", "gfw-domains")
                            put("type", "local")
                            put("format", "source")
                            put("path", File(workDir, "gfw-domains.json").absolutePath)
                        })
                        add(buildJsonObject {
                            put("tag", "china-ips")
                            put("type", "local")
                            put("format", "source")
                            put("path", File(workDir, "china-ips.json").absolutePath)
                        })
                    }
                }

                // BypassChina: unknown traffic → direct (most Chinese traffic not in GFW list)
                // Global: all traffic → proxy
                put("final", if (isBypass) "direct" else "proxy")
                put("auto_detect_interface", false)
                put("override_android_vpn", false)
            }
        }

        return config.toString()
    }

    /**
     * Write GFW domain rule-set in sing-box "source" format.
     * Domains from GFWListRules.proxyDomains (GFW-blocked sites).
     */
    private fun writeGfwRuleSet(workDir: File) {
        val sb = StringBuilder()
        sb.append("{\"version\":2,\"rules\":[{\"domain_suffix\":[")
        var first = true
        for (domain in GFWListRules.proxyDomains) {
            val d = domain.removePrefix(".")
            if (d.isBlank()) continue
            if (!first) sb.append(",")
            sb.append("\"").append(d).append("\"")
            first = false
        }
        sb.append("]}]}")
        File(workDir, "gfw-domains.json").writeText(sb.toString())
    }

    /**
     * Write China IP CIDR rule-set in sing-box "source" format.
     * Ranges from ChinaIpRanges.
     */
    private fun writeChinaIpRuleSet(workDir: File) {
        val sb = StringBuilder()
        sb.append("{\"version\":2,\"rules\":[{\"ip_cidr\":[")
        var first = true
        for (cidr in ChinaIpRanges.allCidrs()) {
            if (!first) sb.append(",")
            sb.append("\"").append(cidr).append("\"")
            first = false
        }
        sb.append("]}]}")
        File(workDir, "china-ips.json").writeText(sb.toString())
    }
}
