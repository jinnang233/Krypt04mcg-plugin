package dev.krypt04mcg.relay;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MessageBundleTest {
    @Test void missingCustomLanguageFallsBackWithoutBreakingReload(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("messages_en_us.yml"), "test: fallback\n");
        assertEquals("fallback", MessageBundle.load(directory.toFile(), "typo", Logger.getAnonymousLogger()).text("test"));
        assertFalse(Files.exists(directory.resolve("messages_typo.yml")));
    }

    @Test void existingCustomLanguageRetainsEnglishFallback(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("messages_en_us.yml"), "test: fallback\nother: retained\n");
        Files.writeString(directory.resolve("messages_de_de.yml"), "test: custom\n");
        var bundle = MessageBundle.load(directory.toFile(), "de-DE", Logger.getAnonymousLogger());
        assertEquals("custom", bundle.text("test"));
        assertEquals("retained", bundle.text("other"));
    }

    @Test void untrustedValuesAreNotExpandedOrInterpretedAsColorCodes() {
        var config = new YamlConfiguration();
        config.set("test", "&a%receiver% from %sender%");
        var bundle = new MessageBundle(config, new YamlConfiguration());
        assertEquals(ChatColor.GREEN + "%sender% &c$\\ from Alice",
                bundle.text("test", "receiver", "%sender% &c$\\", "sender", "Alice"));
    }

    @Test void supportsFallbackAndUnknownPlaceholders() {
        var fallback = new YamlConfiguration();
        fallback.set("test", "%sender% %missing%");
        var bundle = new MessageBundle(new YamlConfiguration(), fallback);
        assertEquals("Alice %missing%", bundle.text("test", "sender", "Alice"));
        assertEquals("unknown", bundle.text("unknown"));
    }

    @Test void languageNamesCannotBecomePathsAndAliasesStillWork() {
        assertEquals("en_us", MessageBundle.normalize("../../outside"));
        assertEquals("en_us", MessageBundle.normalize("..\\outside"));
        assertEquals("en_us", MessageBundle.normalize("en\nspoof"));
        assertEquals("zh_cn", MessageBundle.normalize("ZH-HANS"));
        assertEquals("zh_cn", MessageBundle.normalize(null));
        assertEquals("de_de", MessageBundle.normalize("de-DE"));
    }
}
