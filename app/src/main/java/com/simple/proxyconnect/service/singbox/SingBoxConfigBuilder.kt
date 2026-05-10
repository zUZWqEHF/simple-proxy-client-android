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
     * In **Global** mode, all traffic goes through the proxy outbound and DNS
     * resolves via the remote DNS (8.8.8.8 over TCP through proxy).
     *
     * In **BypassChina** mode, sing-box itself handles routing:
     *   - GFW-listed domains → proxy + resolved via remote DNS (avoid poisoning)
     *   - Domestic IPs (China IP CIDR) → direct
     *   - Everything else → direct (final fallback)
     *   - Local DNS (223.5.5.5) used for everything not in GFW list
     *
     * The VPN service excludes its own app from VPN routes via
     * addDisallowedApplication, so sing-box's direct outbound does NOT
     * loop through TUN.
     *
     * NOTE: The previous implementation had a `dns-out` outbound (`type: dns`)
     * paired with a `protocol: dns` route rule, plus a `block` outbound. In
     * sing-box ≥ 1.12 the `dns` outbound type is deprecated and routes that
     * land there can fall through to the implicit `block` outbound, producing
     * the spurious `outbound/block[block]: operation not permitted` errors
     * observed for DoH/DoT targets such as `dns.google:443` and
     * `chrome.cloudflare-dns.com:443`. We remove both: DNS handling lives in
     * the `dns` section only, and there is no `block` outbound.
     */
    fun build(
        node: ProxyNode,
        routingMode: RoutingMode,
        localMixedPort: Int = 2080,
        @Suppress("UNUSED_PARAMETER") localDnsPort: Int = 6450,
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

            // ── DNS (sing-box 1.12+ new format) ──
            // Old: `{ "address": "tcp://8.8.8.8", "detour": "proxy" }`
            // New: `{ "type": "tcp", "address": "8.8.8.8", "detour": "proxy" }`
            //
            // NOTE: sing-box 1.13 rejects `detour: "direct"` on a DNS server —
            // it's a no-op and the runtime errors out with
            // `start dns/...: detour to an empty direct outbound makes no
            // sense`. So the local DNS server is plain UDP without a detour
            // (the `direct` outbound is reached automatically since the app
            // is excluded from the VPN route).
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("type", "tcp")
                        put("tag", "remote-dns")
                        put("server", "8.8.8.8")
                        put("detour", "proxy")
                    })
                    if (isBypass) {
                        add(buildJsonObject {
                            put("type", "udp")
                            put("tag", "direct-dns")
                            put("server", "223.5.5.5")
                        })
                    }
                }

                if (isBypass) {
                    putJsonArray("rules") {
                        // GFW domains → resolve via remote DNS (avoid local DNS poisoning)
                        add(buildJsonObject {
                            putJsonArray("rule_set") { add(JsonPrimitive("gfw-domains")) }
                            put("server", "remote-dns")
                        })
                    }
                }

                put("strategy", "prefer_ipv4")
                put("final", if (isBypass) "direct-dns" else "remote-dns")
            }

            // ── Inbounds (sing-box 1.13 removed inbound `sniff` / `sniff_override_destination`) ──
            putJsonArray("inbounds") {
                // Single mixed (HTTP+SOCKS) inbound. SimpleVpnService funnels all
                // app TCP through here via SOCKS5; DNS is intercepted at the TUN
                // layer by SimpleVpnService.handleDns and never reaches sing-box.
                add(buildJsonObject {
                    put("type", "mixed")
                    put("tag", "mixed-in")
                    put("listen", "127.0.0.1")
                    put("listen_port", localMixedPort)
                })
            }

            // ── Outbounds ──
            // Only `proxy` and `direct` are needed. We deliberately omit:
            //   - `dns-out` (type=dns): deprecated in sing-box 1.12+, the
            //     route `protocol: dns` rule that paired with it sometimes
            //     fell through to an implicit block, producing
            //     `outbound/block[block]: operation not permitted` for
            //     DoH targets like dns.google:443.
            //   - `block`: nothing in our config wants to block traffic;
            //     keeping it around invited the same fall-through bug.
            putJsonArray("outbounds") {
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "proxy")
                    put("server", "127.0.0.1")
                    put("server_port", localSimpleBridgePort)
                    put("version", "5")
                })
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
            }

            // ── Route ──
            putJsonObject("route") {
                putJsonArray("rules") {
                    // Sniff TLS/HTTP early so subsequent rules can match on the
                    // sniffed domain (e.g. dns.google → matches gfw-domains).
                    add(buildJsonObject {
                        put("action", "sniff")
                        put("timeout", "500ms")
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
                        // Private/loopback/multicast → direct (don't try to proxy them)
                        add(buildJsonObject {
                            put("ip_is_private", true)
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
                // sing-box ≥ 1.12 wants an explicit fallback resolver for any
                // outbound that dials by domain. All our outbounds dial by IP
                // (proxy → 127.0.0.1, direct → physical NIC), but specifying
                // this silences the deprecation warning and is forward-
                // compatible with 1.14, where the field becomes mandatory.
                put("default_domain_resolver", if (isBypass) "direct-dns" else "remote-dns")
            }

            // ── Experimental: clash API for live routing introspection ──
            // Bind only to loopback so a curl from `run-as` can query
            // /connections, /traffic and /logs while diagnosing routing.
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:9090")
                    put("external_ui", "")
                    put("default_mode", "rule")
                }
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
