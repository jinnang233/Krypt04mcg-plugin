# Changelog

## 1.8.2 — 2026-10-08

- Preserve in-flight vanilla-chat/private-command fragment assemblies when the pending-message cache is full. New message IDs are ignored until completion, timeout, disconnect or reload releases capacity; existing messages can still finish.
- Keep the existing fixed assembly deadline, validation, buffered-text limits and configuration defaults.

This fix applies to the plugin's chat fragment collector. Custom payload forwarding and raw encrypted stream API/file traffic do not use that collector and retain their existing behavior. No wire format or API change is required by the client or reverse-forwarding mod.

Build target remains Java 25 and Spigot 26.3. ProtocolLib is optional; its packet interception on 26.3 has not been verified. Restart the plugin/server to load the updated JAR, or use a supported plugin replacement procedure; configuration reload alone does not replace running code.
