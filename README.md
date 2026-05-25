# Krypt04McgRelay

> [!WARNING]
> This codebase was **generated with AI assistance**. Review the implementation carefully, especially the cryptography, key storage, networking behavior, and dependency configuration, before using it in any real environment.

> [!WARNING]
> Krypt04Mcg is **experimental** software and has not undergone independent security auditing. The protocol, implementation, and cryptographic design **may contain vulnerabilities or design flaws**. Do not rely on this mod to protect highly sensitive, important, or production-critical data. If you require mature and battle-tested end-to-end encrypted communication, consider using established tools such as Signal or SimpleX instead.

## Disclaimer

Krypt04Mcg is an **experimental** plugin project. Its build environment, release artifacts, dependencies, and runtime behavior are provided as-is, with **no guarantee** that they are secure, trustworthy, virus-free, or suitable for any particular use. Before installing or running any downloaded artifact, **scan it with VirusTotal** or a comparable malware-scanning service whenever possible.

**Never use this project in production environments, and never use it to protect sensitive, important, private, regulated, or high-value data. This project is not expected to receive active long-term maintenance, security response, or compatibility updates.**

Krypt04McgRelay is a Bukkit/Spigot plugin that privately relays Krypt04Mcg encrypted chat packets. When a player sends a matching encrypted fragment, the server cancels the normal chat broadcast, rebuilds the packet header, reads the target receiver, and forwards the original encrypted fragments only to that player.

The server console never prints the encrypted payload. It logs a localized summary such as:

```text
Alice 向 Bob 发送了加密消息。
```

## Supported Server

- Built against Spigot API `26.1.2-R0.1-SNAPSHOT`.
- Uses Java `25` because Minecraft/Spigot 26.1 requires Java 25 or later.
- Uses Bukkit API only, no NMS or CraftBukkit internals.

## Packet Format

The plugin recognizes the fragment format used by the reference Krypt04Mcg client code:

```text
[KRYPT04MCG] <messageId> <index> <total> <payload>
```

It waits until all fragments for the same sender and message id arrive, decodes the packet header, then routes the original fragment lines to the `receiver` stored in the encrypted packet metadata.

Forwarded fragments are sent to the receiver using the vanilla-style chat shape:

```text
<Alice> [KRYPT04MCG] <messageId> <index> <total> <payload>
```

This matches clients that parse incoming chat with:

```regex
^<(?<player>[^>]+)>\s*(?<message>.*)$
```

## Configuration

`config.yml` is created on first run:

```yaml
language: zh_cn
echo-to-sender: false
notify-offline-receiver: true
notify-malformed-fragment: true
enforce-sender-match: true
fragment-timeout-seconds: 120
max-pending-messages: 128
max-fragments-per-message: 256
```

Language files are also created in the plugin data folder:

- `messages_zh_cn.yml`
- `messages_en_us.yml`

Set `language` to `zh_cn` or `en_us`, then run:

```text
/kryptrelay reload
```

## Build

With Maven installed:

```bash
mvn package
```

The plugin jar will be generated under `target/`.

## GitHub Actions

The repository includes a GitHub Actions workflow at `.github/workflows/build.yml`.
It builds the plugin on pushes, pull requests, and manual runs, then uploads the generated jar as a workflow artifact.

## Install

1. Build the jar or use `target/Krypt04McgRelay-1.0.0.jar`.
2. Put it into the server `plugins/` directory.
3. Restart the server.
4. Edit the generated config if needed.
5. Run `/kryptrelay reload` after config or language changes.

## Notes

- This plugin is a relay only. It does not encrypt or decrypt message bodies.
- The client-side Krypt04Mcg implementation must keep the packet header format compatible with the reference `PacketCodec`.
- By default, the plugin rejects packets whose internal sender field does not match the player who sent the chat fragments.

## License

This project is licensed under the Zero-Clause BSD license. See [LICENSE](LICENSE).
