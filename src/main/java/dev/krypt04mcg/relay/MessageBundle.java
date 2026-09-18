package dev.krypt04mcg.relay;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Logger;

public final class MessageBundle {
    private static final Pattern PLACEHOLDER = Pattern.compile("%([a-zA-Z0-9_]+)%");
    private final FileConfiguration messages;
    private final FileConfiguration fallback;

    MessageBundle(FileConfiguration messages, FileConfiguration fallback) {
        this.messages = messages;
        this.fallback = fallback;
    }

    public static MessageBundle load(Krypt04McgRelayPlugin plugin, String language) {
        for (String builtIn : new String[]{"messages_en_us.yml", "messages_zh_cn.yml"}) {
            if (!new File(plugin.getDataFolder(), builtIn).isFile()) plugin.saveResource(builtIn, false);
        }
        return load(plugin.getDataFolder(), language, plugin.getLogger());
    }

    static MessageBundle load(File directory, String language, Logger logger) {
        String normalized = normalize(language);
        String fileName = "messages_" + normalized + ".yml";
        File file = new File(directory, fileName);
        File fallbackFile = new File(directory, "messages_en_us.yml");
        if (!file.isFile()) {
            logger.warning("Language file " + fileName + " not found; using messages_en_us.yml");
            file = fallbackFile;
        }
        return new MessageBundle(YamlConfiguration.loadConfiguration(file),
                YamlConfiguration.loadConfiguration(fallbackFile));
    }

    public String text(String key, String... placeholders) {
        String value = ChatColor.translateAlternateColorCodes('&', messages.getString(key, fallback.getString(key, key)));
        if (placeholders.length == 0) return value;
        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            replacements.put(placeholders[i], String.valueOf(placeholders[i + 1]));
        }
        // Values are literal data: do not expand placeholders or color codes inside them.
        return PLACEHOLDER.matcher(value).replaceAll(match -> Matcher.quoteReplacement(
                replacements.getOrDefault(match.group(1), match.group())));
    }

    static String normalize(String language) {
        if (language == null || language.isBlank()) {
            return "zh_cn";
        }
        String normalized = language.toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[a-z0-9_]{1,32}")) return "en_us";
        return switch (normalized) {
            case "en", "en_us" -> "en_us";
            case "zh", "zh_cn", "zh_hans", "zh_cn_simplified" -> "zh_cn";
            default -> normalized;
        };
    }
}
