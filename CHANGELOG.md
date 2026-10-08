# Changelog

## 1.8.3 — 2026-10-08

- Bound pending vanilla-chat/private-command assemblies to 16 per player UUID, within the configured global limit. One player can no longer occupy all 128 default slots with unfinished message IDs.
- Bound one player's buffered payload and raw-line text to 1,048,576 characters, within the existing 4,194,304-character global budget. Over-budget growth releases only the offending assembly. Completion, expiry, quit and reload release quotas.
- Canonicalize hexadecimal message ID case, preventing one message from splitting into multiple assemblies. Reject null sender/fragment arguments from direct callers.

Configuration keys, defaults and wire formats are unchanged. These quotas cover vanilla chat/private commands; custom payload and raw stream forwarding do not use this collector. The client's new progress HUD is local to Krypt04Mcg 0.28.0 and does not require a server-side progress protocol.

Details: [sender admission and text-budget audit](docs/security/2026-10-08-sender-quotas.md). This targeted fix is not a complete denial-of-service defense; actual Spigot/ProtocolLib networking was not exercised.

## 1.8.2 — 2026-10-08

- Preserve in-flight vanilla-chat/private-command fragment assemblies when the pending-message cache is full. New message IDs are ignored until completion, timeout, disconnect or reload releases capacity; existing messages can still finish.
- Keep the existing fixed assembly deadline, validation, buffered-text limits and configuration defaults.

This fix applies to the plugin's chat fragment collector. Custom payload forwarding and raw encrypted stream API/file traffic do not use that collector and retain their existing behavior. No wire format or API change is required by the client or reverse-forwarding mod.

Build target remains Java 25 and Spigot 26.3. ProtocolLib is optional; its packet interception on 26.3 has not been verified. Restart the plugin/server to load the updated JAR, or use a supported plugin replacement procedure; configuration reload alone does not replace running code.
