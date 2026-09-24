# Krypt04McgRelay

[![Build](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/build.yml)[![Build and Release](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/release.yml/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/release.yml)[![CodeQL](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/github-code-scanning/codeql)

> [!WARNING]
> This codebase was **generated with AI assistance**. Review the implementation carefully, especially the cryptography, key storage, networking behavior, and dependency configuration, before using it in any real environment.
>
> If possible, please run it in an **ISOLATED** environment, such as a virtual machine, to avoid potential security risks from build artifacts, such as the possibility that the maintainer’s computer has been infected with malware.
>
> If you discover any code security issues, or any copyright or licensing concerns, please report them in Issues. Thank you for your understanding.

> [!WARNING]
> Krypt04Mcg is **EXPERIMENTAL** software and has not undergone independent security auditing. The protocol, implementation, and cryptographic design **may contain vulnerabilities or design flaws**. Do not rely on this mod to protect highly sensitive, important, or production-critical data. If you require mature and battle-tested end-to-end encrypted communication, consider using established tools such as Signal or SimpleX instead.

## Disclaimer

Krypt04Mcg is an **EXPERIMENTAL** plugin project. Its build environment, release artifacts, dependencies, and runtime behavior are provided as-is, with **NO GUARANTEE** that they are secure, trustworthy, virus-free, or suitable for any particular use. Before installing or running any downloaded artifact, **scan it with VirusTotal** or a comparable malware-scanning service whenever possible.

**Never use this project in production environments, and NEVER use it to protect sensitive, important, private, regulated, or high-value data. This project is not expected to receive active long-term maintenance, security response, or compatibility updates.**

Krypt04McgRelay is a Bukkit/Spigot plugin that privately relays Krypt04Mcg encrypted chat packets. When a player sends a matching encrypted fragment, the server cancels the normal chat broadcast, rebuilds the packet header, reads the target receiver, and forwards the original encrypted fragments only to that player.

The server console never prints the encrypted payload. It logs a localized summary such as:

```text
Alice sent an encrypted message to Bob.
```

## Supported Server

- Built against Spigot API `26.3-R0.1-SNAPSHOT`.
- Uses Java `25` for Minecraft/Spigot 26.3.
- ProtocolLib is optional for the chat spam-kick bypass. The chat relay and custom payload channels work without it.
- Uses Bukkit API plus ProtocolLib packet interception, no NMS or CraftBukkit internals.

## Packet Format

The plugin recognizes the fragment format used by the reference Krypt04Mcg client code:

```text
[KRYPT04MCG] <messageId> <index> <total> <payload>
```

It waits until all fragments for the same sender and message id arrive, decodes the packet header, then routes the original fragment lines to the `receiver` stored in the encrypted packet metadata.

Packet protocol versions `1`, `2`, `3`, and `4` are accepted. Protocol v3 no longer stores fragment metadata inside the encrypted packet, and omits KEM or signature algorithm identifiers when the packet type or flags do not use them. Protocol v4 session messages add a session ID and sequence number after the message ID and omit the signature field entirely. The relay handles all four packet types, including session exchanges and v4 session messages, while preserving the original encrypted fragments.

The custom payload channel `krypt04mcg:chat_fragment` is also supported. Its wire format remains two Minecraft UTF-8 strings followed by a VarInt version: client-to-server sends `(receiver, fragment, version)`, and server-to-client sends `(sender, fragment, version)`. The relay obtains the sender from the authenticated player connection and forwards the fragment and version unchanged. The receiving client must be listening on this channel.

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
announce-plugin-installed: true
echo-to-sender: false
notify-offline-receiver: true
notify-malformed-fragment: true
enforce-sender-match: true
kick-krypt04mcg-chat-spam: false
fragment-timeout-seconds: 120
max-pending-messages: 128
max-fragments-per-message: 256
```

`kick-krypt04mcg-chat-spam` is `false` by default. When it is `false`, Krypt04Mcg fragments do not count toward Minecraft chat spam kicks; when it is `true`, Krypt04Mcg fragments use the normal spam kick behavior. Non-Krypt04Mcg chat is not changed by this option.

The spam-kick bypass also covers Krypt04Mcg fragments sent through vanilla private-message commands (`/tell`, `/msg`, and `/w`). Other commands and ordinary private messages are not intercepted.

ProtocolLib is declared as a `provided` dependency and is not bundled into the Krypt04McgRelay jar. For Minecraft/Spigot `26.2`, use a compatible GitHub development build. ProtocolLib's published source still lists `26.2` as its highest tested version, so packet interception on `26.3` is not yet verified. Without an enabled, compatible ProtocolLib build, the relay still handles ordinary chat and custom payloads, but cannot bypass vanilla chat spam kicks for encrypted fragments or private-message commands.

Language files are also created in the plugin data folder:

- `messages_zh_cn.yml`
- `messages_en_us.yml`

Set `language` to `zh_cn` or `en_us`, then run:

```text
/kryptrelay reload
```

Set `announce-plugin-installed` to `false` if you do not want players to receive the plugin-installed notice. The notice tells compatible mod users to enable "Shadow Listen Mode".

## Build

With Maven installed:

```bash
mvn package
```

The plugin jar will be generated under `target/`.

## GitHub Actions

The repository includes a GitHub Actions workflow at `.github/workflows/build.yml`.
It builds the plugin on pushes, pull requests, and manual runs, then uploads the generated jar as a workflow artifact.

Release publishing is handled by `.github/workflows/release.yml`. Push a tag such as `v1.0.1`, or run the workflow manually, to build the plugin and create a GitHub Release. If the `RELEASE_SIGN_KEY` secret is configured with a PEM private key, release jars are signed and the public key is uploaded with the release assets.

## Install

1. Build the jar or download it from a release.
2. Put it into the server `plugins/` directory.
3. To enable the chat spam-kick bypass, install a ProtocolLib build compatible with your server in the `plugins/` directory.
4. Restart the server.
5. Edit the generated config if needed.
6. Run `/kryptrelay reload` after config or language changes.

## Notes

- This plugin is a relay only. It does not encrypt or decrypt message bodies.
- The client-side Krypt04Mcg implementation must keep the packet header format compatible with the reference `PacketCodec`.
- By default, the plugin rejects packets whose internal sender field does not match the player who sent the chat fragments.

## License

This project is licensed under the The Unlicense. See [LICENSE](LICENSE).

### Optional public-key and file channels

The relay additionally registers `krypt04mcg:public_key` and `krypt04mcg:file_share`.
Both use the existing wire layout: Minecraft UTF peer, UTF fragment, VarInt version (1).
Each fragment contains `transferUUID:index:total:data` (zero-based index; at most 12,100 characters including header).
The server replaces peer with the authenticated sender name. Only the public-key channel accepts
receiver `*` to broadcast to other online subscribers. Files are always directed to one receiver.
The relay forwards opaque encrypted file data; client settings default to disabling file sending and receiving.

The relay enforces per-source ingress budgets of 256 packets/second (512 burst) and 2 MiB/second (4 MiB burst).
Outgoing bytes are charged for every recipient, including broadcasts: 8 MiB/second per source (32 MiB burst)
and 32 MiB/second globally (64 MiB burst). Excess traffic is dropped without delivery acknowledgements;
retry transfers that do not complete. Normal file transfers paced at four chunks per client tick fit these budgets.
Malformed UTF-8 and overflowing VarInts are rejected. Malformed-packet diagnostics use fine-level logging.

### Resource limits

Chat fragments use a 1,024-entry inbox instead of scheduling a task per packet. Each server tick
processes at most 64 incoming fragments and 64 outgoing sends, interleaving them and checking a shared
2 ms elapsed-time budget between operations (one decode or send may take longer). Completed messages
are sent incrementally from an outbox capped at 64 messages and 1,048,576 characters, with a 10-second
delivery deadline. Excess ingress and overflowing or expired deliveries are dropped. Disconnects
remove affected deliveries; reload and disable empty both queues and cancel the processing task.
Incomplete messages expire from their first fragment using a monotonic clock, even when traffic stops;
duplicates do not refresh that deadline. Disconnects release pending fragments. Fragment payloads and
raw lines share a 4,194,304-character budget, in addition to the configured message and fragment limits.
The timeout is clamped to 5–3,600 seconds, and both count limits to 1–1,024.

Chat and custom-payload transports each enforce aggregate ingress budgets of 2,048 packets/second
(4,096 burst) and 8 MiB/second (16 MiB burst), as well as the per-source budgets above.
Each transport also caps outgoing sends at 2,048/second per source (4,096 burst) and 8,192/second
globally (16,384 burst), in addition to outgoing byte budgets. Public-key broadcast candidate scans
have separate budgets with those same limits, including candidates that do not subscribe.
Chat rejection warnings and related notifications are limited to once per second globally.
Clients should retry incomplete messages after overload has subsided.

Missing custom language files fall back to English without interrupting reload. Language identifiers
are limited to letters, digits and underscores after alias normalization; message placeholder values
are inserted literally, without expanding embedded placeholders or `&` color codes.
