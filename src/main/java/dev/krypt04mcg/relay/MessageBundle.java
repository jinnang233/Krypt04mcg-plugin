package dev.krypt04mcg.relay;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;

public final class MessageBundle {
    private final FileConfiguration messages;
    private final FileConfiguration fallback;

    private MessageBundle(FileConfiguration messages, FileConfiguration fallback) {
        this.messages = messages;
        this.fallback = fallback;
    }

    public static MessageBundle load(Krypt04McgRelayPlugin plugin, String language) {
        plugin.saveResource("messages_en_us.yml", false);
        plugin.saveResource("messages_zh_cn.yml", false);

        String normalized = normalize(language);
        String fileName = "messages_" + normalized + ".yml";
        File file = new File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            plugin.saveResource(fileName, false);
        }

        File fallbackFile = new File(plugin.getDataFolder(), "messages_en_us.yml");
        return new MessageBundle(YamlConfiguration.loadConfiguration(file),
                YamlConfiguration.loadConfiguration(fallbackFile));
    }

    public String text(String key, String... placeholders) {
        String value = messages.getString(key, fallback.getString(key, key));
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            value = value.replace("%" + placeholders[i] + "%", placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return "zh_cn";
        }
        String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "en", "en_us" -> "en_us";
            case "zh", "zh_cn", "zh_hans", "zh_cn_simplified" -> "zh_cn";
            default -> normalized;
        };
    }
}
