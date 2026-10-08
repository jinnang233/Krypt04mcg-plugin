# Vanilla-chat collector sender quotas — 2026-10-08

Plugin 1.8.2 preserved admitted assemblies at the global capacity limit but let one player UUID reserve all 128 default pending slots. A standalone probe using the same collector API reproduces the denial of another player's completed message against the old collector; the fixed implementation allows the second player to complete despite the first player's ID flood. Existing ingress rate limits delay the flood but do not partition the long-lived assembly pool.

Version 1.8.3 limits pending assemblies to 16 per player UUID, within the configured global cap, and bounds buffered payload plus original-line text to 1,048,576 characters per player within the existing 4,194,304-character global budget. These are character accounting limits, not exact JVM heap sizes. UUID comes from the Bukkit player, not from unverified packet contents. Configuring a global cap below 16 can still make that smaller cap the bottleneck.

New IDs are dropped at either message quota; admitted assemblies can continue. Fragment growth exceeding a text budget removes only the offending assembly and releases its accounted text. Completion, timeout, player quit and clear/reload release both resources. Message IDs are normalized to lowercase hexadecimal before lookup. The configured fixed deadline and maximum fragment count remain unchanged.

Regression tests cover a 128-ID single-player flood with another player completing, independent per-player text growth, the retained global budget across multiple players, release on completion/quit/expiry/clear and ID case aliases. Raw-stream/custom-payload relay tests remain part of Maven verification. Actual Spigot/ProtocolLib networking was not exercised.

Maven verification for 1.8.3 passed all 126 tests with no failures, errors or skips, including the collector's 11 tests. The built JAR's plugin metadata was verified as 1.8.3.

This collector is used only by vanilla-chat/private-command routing. Client HUD progress requires no relay progress packets. These quotas mitigate single-player resource monopolization, not coordinated floods or all denial-of-service attacks.
