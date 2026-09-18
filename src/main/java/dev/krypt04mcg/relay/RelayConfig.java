package dev.krypt04mcg.relay;

import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;

public record RelayConfig(
        String language,
        boolean announcePluginInstalled,
        boolean echoToSender,
        boolean notifyOfflineReceiver,
        boolean notifyMalformedFragment,
        boolean enforceSenderMatch,
        boolean kickKrypt04McgChatSpam,
        Duration fragmentTimeout,
        int maxPendingMessages,
        int maxFragmentsPerMessage
) {
    public static RelayConfig from(JavaPlugin plugin) {
        return new RelayConfig(
                plugin.getConfig().getString("language", "zh_cn"),
                plugin.getConfig().getBoolean("announce-plugin-installed", true),
                plugin.getConfig().getBoolean("echo-to-sender", false),
                plugin.getConfig().getBoolean("notify-offline-receiver", true),
                plugin.getConfig().getBoolean("notify-malformed-fragment", true),
                plugin.getConfig().getBoolean("enforce-sender-match", true),
                plugin.getConfig().getBoolean("kick-krypt04mcg-chat-spam", false),
                Duration.ofSeconds(Math.clamp(plugin.getConfig().getLong("fragment-timeout-seconds", 120), 5, 3600)),
                Math.clamp(plugin.getConfig().getInt("max-pending-messages", 128), 1, 1024),
                Math.clamp(plugin.getConfig().getInt("max-fragments-per-message", 256), 1, 1024)
        );
    }
}
