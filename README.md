# GreyRecon

Network recon & security toolkit for Android. Discover and classify devices on your own WiFi network, run recon tools against domains/IPs, and drop into a real interactive terminal — all with no forced account and no ads.

**Package:** `com.greyrecon.app` · **Privacy Policy:** https://canuk40.github.io/GreyRecon/privacy-policy.html

## Features

- **Multi-method device discovery** — native netlink ARP/NDP (JNI), mDNS, UPnP, and active TCP-probe scanning, merged and deduplicated.
- **Device classification** — gateway detection, mDNS service-type signatures, port fingerprints, and vendor-keyword matching.
- **Device History** — devices seen over time, custom names, new-device alerts, IP-change timeline.
- **Recon tools** — subnet calculator, DNS lookup, WHOIS/RDAP (including RFC 9537 redaction parsing), Certificate Transparency monitoring, typosquat/homograph domain checking, BLE scanning.
- **Security checks** — TLS/cipher analysis, security-header checks, common exposure/debug-panel detection, default-credential testing, SNMP community-string brute force, NVD/EPSS-prioritized CVE lookups.
- **Real interactive terminal** — a genuine PTY-backed shell (vendored, Apache-2.0 `terminal-emulator`/`terminal-view`), a cross-compiled `toybox` for real Unix tooling, and `greyrecon-pkg`, an original package manager for installing additional CLI tools (`.grpkg` format, checksum-verified).
- **MCP server (Pro)** — exposes scan data as tools a self-hosted [nanobot](https://github.com/HKUDS/nanobot) instance (or any MCP client) can query conversationally.
- **BYOK** — Shodan, DeepSeek, and Anthropic integrations use your own API keys, stored via `EncryptedSharedPreferences`. No GreyRecon-owned backend sits between you and any of these services.

## Building

Standard Gradle/Android Studio project. Requires the Android NDK (native discovery/exec-shim code) and CMake.

```bash
./gradlew assembleDebug
```

A signed release build additionally needs `keystore/keystore.properties` (not included — see `.gitignore`) and, if you want crash reporting, your own `app/google-services.json` from a Firebase project.

## License

The vendored `terminal-emulator` and `terminal-view` modules are Apache-2.0 (see `NOTICE.md` in each). The rest of this repository is not currently licensed for reuse.
