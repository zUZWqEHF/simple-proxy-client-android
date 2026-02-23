# Simple Proxy Client (Android)

An Android client for [SimpleProtocol](https://github.com/zUZWqEHF/simple-proxy-server) — a lightweight, encrypted proxy built on AES-256-GCM with mux multiplexing.

## Architecture

```
┌──────────────────────────── Android Device ────────────────────────┐
│                                                                     │
│  Apps (browser, etc.)                                               │
│       │  TCP / UDP                                                  │
│       ▼                                                             │
│  ┌─────────┐     IP packets      ┌──────────────────────────┐      │
│  │ TUN fd  │ ──────────────────→ │ Packet Reader (userspace │      │
│  │ 10.0.0.2│ ←──────────────── │  TCP stack + DNS proxy)   │      │
│  └─────────┘    crafted packets  └──────────┬───────────────┘      │
│                                              │ TCP connect          │
│                                              ▼                      │
│                                   ┌──────────────────────┐         │
│                                   │ sing-box (mixed-in)  │         │
│                                   │ 127.0.0.1:2080       │         │
│                                   │                      │         │
│                                   │ ┌──────────────────┐ │         │
│                                   │ │  Route decision  │ │         │
│                                   │ │  • intl → proxy  │ │         │
│                                   │ │  • domestic→direct│ │         │
│                                   │ │  • DNS → dns-out │ │         │
│                                   │ └──┬──────────┬────┘ │         │
│                                   │    │          │      │         │
│                                   │  proxy      direct   │         │
│                                   │  outbound   outbound │         │
│                                   └──┬──────────┬────────┘         │
│                                      │          │                   │
│                            ┌─────────┘          └──→ Physical NIC  │
│                            ▼                       (bypass TUN)     │
│                 ┌────────────────────────┐                          │
│                 │ SimpleProtocol Bridge   │                          │
│                 │ (SOCKS5 → Mux Tunnel)  │                          │
│                 │ 127.0.0.1:16080        │                          │
│                 └──────────┬─────────────┘                          │
│                            │ AES-256-GCM encrypted                  │
│                            │ Mux multiplexed TCP                    │
└────────────────────────────┼────────────────────────────────────────┘
                             ▼
                  ┌────────────────────┐
                  │ SimpleProtocol     │
                  │ Server (remote)    │
                  │                    │
                  │  Mux demux → dial  │
                  │  target servers    │
                  └────────────────────┘
```

### Key Design Points

- **Userspace TCP stack** — `SimpleVpnService` reads raw IP packets from the TUN interface, implements a SYN/ACK/FIN state machine, and reassembles TCP streams before forwarding them to the sing-box SOCKS port.
- **sing-box routing** — Uses `rule_set` (international domain list + domestic IP list) to decide whether traffic goes through the `proxy` or `direct` outbound.
- **`addDisallowedApplication`** — Excludes this app from the tunnel route so that the `direct` outbound in sing-box goes through the physical NIC directly, avoiding TUN loopback.
- **SimpleProtocol Bridge** — A local SOCKS5 service that accepts `proxy` outbound from sing-box and forwards it through an AES-256-GCM + HKDF-SHA256 encrypted mux tunnel to the remote server.
- **Mux multiplexing** — All proxy streams share a single encrypted TCP connection, reducing handshake overhead.

## Features

- SimpleProtocol nodes only.
- Two routing modes: **Global** and **Smart Routing** (bypass domestic traffic).
- sing-box core auto-downloaded and bundled into `jniLibs` (enabled by default).
- Local bridge converts sing-box SOCKS outbound into a SimpleProtocol encrypted tunnel.
- Domain rule list is pre-generated and bundled statically.

## Routing Modes

### Global
- Route `final = proxy` — all traffic goes through the remote server.
- All DNS queries use the remote DNS resolver.

### Smart Routing (Bypass Domestic)
- Route `final = direct` — unmatched traffic goes direct.
- International / blocked domains are routed through `proxy`.
- Domestic domains and domestic IP ranges are routed `direct`.
- DNS: domestic domains (`.cn` / `.com.cn`) use the local resolver; everything else uses the remote resolver.

> Unit tests for config generation: `app/src/test/java/com/simple/proxyconnect/service/singbox/SingBoxConfigBuilderTest.kt`

## Build

```bash
export JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"

./gradlew :app:assembleDebug \
  -PSING_BOX_AUTO_DOWNLOAD=true \
  -PSING_BOX_VERSION=1.12.22 \
  -PSING_BOX_TARGET_ABIS=all
```

### Reduce APK Size by Target ABI

- Use `SING_BOX_TARGET_ABIS` to include only specific ABIs (comma-separated):
  - Example: `-PSING_BOX_TARGET_ABIS=arm64-v8a`
- For Google Play publishing, use **AAB** (`bundleRelease`):
  - Keep `SING_BOX_TARGET_ABIS=all` — Play will deliver only the matching ABI to each device.

## Update Domain Rules

```bash
python3 tools/update_gfwlist.py
```

- Fetches the latest domain rule list and regenerates:
  - `app/src/main/java/com/simple/proxyconnect/service/GeneratedGfwDomains.kt`

## Run Tests

```bash
./gradlew :app:testDebugUnitTest
```

## Repository Layout

| Repository | Description |
|------------|-------------|
| [simple-proxy-client-android](https://github.com/zUZWqEHF/simple-proxy-client-android) | Android client (this repo) |
| [simple-proxy-server](https://github.com/zUZWqEHF/simple-proxy-server) | Server |
| [simple-proxy-client-ios](https://github.com/zUZWqEHF/simple-proxy-client-ios) | iOS client |

The `server/` directory is gitignored — the server code lives in its own repository.

## Server

See [simple-proxy-server](https://github.com/zUZWqEHF/simple-proxy-server) for server documentation and downloads.

## Changelog

| Version | versionCode | Changes |
|---------|-------------|---------|
| 1.2.2 | 8 | Fix: connection stuck on "Connecting" when server is unreachable |
| 1.2.1 | 7 | Fix: slow first connection in Global mode; fix mode-switch stuck; DNS selection per routing mode |
| 1.2.0 | 6 | Architecture rewrite: sing-box routing + rule_set; `addDisallowedApplication` to avoid TUN loopback |
| 1.1.0 | 5 | Mux multiplexing protocol |
| 1.0.3 | 4 | Bug fixes |

## Privacy Policy

[Privacy Policy](https://github.com/zUZWqEHF/simple-proxy-server/blob/main/PRIVACY_POLICY.md)

## License

MIT
