# Krypt04McgRelay

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
Alice 向 Bob 发送了加密消息。
```

## Supported Server

- Built against Spigot API `26.2-R0.1-SNAPSHOT`.
- Uses Java `25` because Minecraft/Spigot 26.2 requires Java 25 or later.
- Requires ProtocolLib installed as a separate server plugin.
- Uses Bukkit API plus ProtocolLib packet interception, no NMS or CraftBukkit internals.

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

ProtocolLib is declared as a `provided` dependency and is not bundled into the Krypt04McgRelay jar. For Minecraft/Spigot `26.2`, install the ProtocolLib GitHub `dev-build` separately in the server `plugins/` folder; the `5.4.0` release is not sufficient for this server version.

Language files are also created in the plugin data folder:

- `messages_zh_cn.yml`
- `messages_en_us.yml`

Set `language` to `zh_cn` or `en_us`, then run:

```text
/kryptrelay reload
```

Set `announce-plugin-installed` to `false` if you do not want players to receive the plugin-installed notice. The notice tells compatible mod users to enable "Shadow Listen Mode" / "影听模式".

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

1. Build the jar or use `target/Krypt04McgRelay-1.0.7.jar`.
2. Put it into the server `plugins/` directory.
3. Install the ProtocolLib GitHub `dev-build` separately in the server `plugins/` directory.
4. Restart the server.
5. Edit the generated config if needed.
6. Run `/kryptrelay reload` after config or language changes.

## Notes

- This plugin is a relay only. It does not encrypt or decrypt message bodies.
- The client-side Krypt04Mcg implementation must keep the packet header format compatible with the reference `PacketCodec`.
- By default, the plugin rejects packets whose internal sender field does not match the player who sent the chat fragments.

## License

This project is licensed under the Zero-Clause BSD license. See [LICENSE](LICENSE).
