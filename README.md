# Krypt04McgRelay

[![Build](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/build.yml/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/build.yml)
[![Build and Release](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/release.yml/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/release.yml)
[![CodeQL](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/github-code-scanning/codeql/badge.svg)](https://github.com/jinnang233/Krypt04mcg-plugin/actions/workflows/github-code-scanning/codeql)

> [!WARNING]
> This codebase was generated with AI assistance. Review the implementation carefully, especially its networking behavior and dependency configuration, before using it in any real environment.
>
> If possible, run it in an isolated environment and scan downloaded build artifacts with VirusTotal or a comparable service. Report security or licensing concerns through Issues.

> [!WARNING]
> Krypt04Mcg is experimental software and has not undergone an independent security audit. The protocol and cryptographic design may contain vulnerabilities. Do not rely on it to protect sensitive or production-critical data; use a mature end-to-end encrypted tool for those cases.

## Disclaimer

Krypt04McgRelay and its build artifacts are provided as-is, with no guarantee that they are secure, trustworthy, virus-free, or fit for a particular purpose. This project is not intended for production environments or high-value data.

Krypt04McgRelay is a small Bukkit/Spigot plugin that relays encrypted chat fragments over the standard Plugin Messaging API. It does not inspect, encrypt, or decrypt the fragment body and does not require ProtocolLib or NMS.

## Requirements

- Spigot API `26.2-R0.1-SNAPSHOT`
- Java 25
- A compatible Krypt04Mcg client mod

## Wire format

Channel: `krypt04mcg:chat_fragment`

Client to server:

```text
receiver, fragment, version
```

Server to client:

```text
sender, fragment, version
```

Strings use Minecraft's UTF format: a VarInt byte length followed by UTF-8 bytes. The version is also a VarInt. Java's `DataInputStream.readUTF()` and `DataOutputStream.writeUTF()` are not compatible with this format.

The server never accepts a sender name from the client. It obtains the authenticated sender from Spigot's `PluginMessageListener` callback and rebuilds the clientbound payload with that name before forwarding it. Offline receivers and receivers that are not listening on the channel are ignored.

## Build

```bash
mvn package
```

The plugin jar is generated under `target/`.

## GitHub Actions

The build workflow compiles pushes, pull requests, and manual runs, then uploads the generated jar. The release workflow builds tagged or manually requested releases and can sign artifacts when `RELEASE_SIGN_KEY` is configured.

## Install

1. Put `target/Krypt04McgRelay-1.1.1.jar` in the server's `plugins/` directory.
2. Restart the server.
3. Connect with a compatible Krypt04Mcg client mod.

## License

This project is licensed under the Zero-Clause BSD license. See [LICENSE](LICENSE).
