package dev.krypt04mcg.relay;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class ProtocolLibChatInterceptor {
    private static final Pattern PRIVATE_MESSAGE_COMMAND = Pattern.compile(
            "^/?(?:minecraft:)?(?:tell|msg|w)\\s+\\S+\\s+(.+)$",
            Pattern.CASE_INSENSITIVE);

    private final Krypt04McgRelayPlugin plugin;
    private final RelayConfig config;
    private final EncryptedChatRelay relay;
    private Runnable unregisterAction;
    private volatile boolean active;

    ProtocolLibChatInterceptor(Krypt04McgRelayPlugin plugin, RelayConfig config, EncryptedChatRelay relay) {
        this.plugin = plugin;
        this.config = config;
        this.relay = relay;
    }

    void register() {
        var manager = ProtocolLibrary.getProtocolManager();
        var listener = new PacketAdapter(plugin, ListenerPriority.LOWEST,
                supportedClientMessagePackets()) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                if (!active || config.kickKrypt04McgChatSpam()) return;
                String message = readMessage(event);
                if (message == null) {
                    return;
                }

                if (!event.getPacketType().equals(PacketType.Play.Client.CHAT)) {
                    message = extractPrivateMessageBody(message);
                    if (message == null) {
                        return;
                    }
                }

                if (!relay.isKrypt04McgMessage(message)) {
                    return;
                }

                Player sender = event.getPlayer();
                event.setCancelled(true);
                relay.handleKrypt04McgMessage(sender, message);
            }
        };
        unregisterAction = () -> manager.removePacketListener(listener);
        active = true;
        manager.addPacketListener(listener);
    }

    void unregister() {
        active = false;
        Runnable cleanup = unregisterAction;
        unregisterAction = null;
        if (cleanup == null) return;
        try {
            cleanup.run();
        } catch (RuntimeException | LinkageError e) {
            plugin.getLogger().warning("ProtocolLib listener cleanup failed: " + e);
        }
    }

    private static List<PacketType> supportedClientMessagePackets() {
        return Stream.of(
                        PacketType.Play.Client.CHAT,
                        PacketType.Play.Client.CHAT_COMMAND,
                        PacketType.Play.Client.CHAT_COMMAND_SIGNED)
                .filter(PacketType::isSupported)
                .toList();
    }

    static String extractPrivateMessageBody(String command) {
        // Bound regex work before vanilla validates an intercepted command packet.
        if (command == null || command.length() > 512) {
            return null;
        }
        Matcher matcher = PRIVATE_MESSAGE_COMMAND.matcher(command);
        return matcher.matches() ? matcher.group(1) : null;
    }

    private static String readMessage(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            return packet.getStrings().read(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
