package dev.krypt04mcg.relay;

import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public record RelayConfig(
        String language,
        boolean echoToSender,
        boolean notifyOfflineReceiver,
        boolean notifyMalformedFragment,
        boolean enforceSenderMatch,
        Duration fragmentTimeout,
        int maxPendingMessages,
        int maxFragmentsPerMessage
) {
    public static RelayConfig from(JavaPlugin plugin) {
        return new RelayConfig(
                plugin.getConfig().getString("language", "zh_cn"),
                plugin.getConfig().getBoolean("echo-to-sender", false),
                plugin.getConfig().getBoolean("notify-offline-receiver", true),
                plugin.getConfig().getBoolean("notify-malformed-fragment", true),
                plugin.getConfig().getBoolean("enforce-sender-match", true),
                Duration.ofSeconds(Math.max(5, plugin.getConfig().getLong("fragment-timeout-seconds", 120))),
                Math.max(1, plugin.getConfig().getInt("max-pending-messages", 128)),
                Math.max(1, plugin.getConfig().getInt("max-fragments-per-message", 256))
        );
    }
}
