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

    /**
     * Creates a protocol lib chat interceptor with the supplied dependencies and initial state.
     *
     * @param plugin the plugin supplied to this operation
     * @param config the config supplied to this operation
     * @param relay the relay supplied to this operation
     */
    ProtocolLibChatInterceptor(Krypt04McgRelayPlugin plugin, RelayConfig config, EncryptedChatRelay relay) {
        this.plugin = plugin;
        this.config = config;
        this.relay = relay;
    }

    /**
     * Registers the supported callbacks and channels for the protocol lib chat interceptor.
     */
    void register() {
        var manager = ProtocolLibrary.getProtocolManager();
        var listener = new PacketAdapter(plugin, ListenerPriority.LOWEST,
                supportedClientMessagePackets()) {
            /**
             * Handles the packet receiving callback for the protocol lib chat interceptor.
             *
             * @param event the event supplied to this operation
             */
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

    /**
     * Removes the registered callbacks and channels from the protocol lib chat interceptor.
     */
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

    /**
     * Performs the supported client message packets operation for the protocol lib chat interceptor.
     *
     * @return the result described above
     */
    private static List<PacketType> supportedClientMessagePackets() {
        return Stream.of(
                        PacketType.Play.Client.CHAT,
                        PacketType.Play.Client.CHAT_COMMAND,
                        PacketType.Play.Client.CHAT_COMMAND_SIGNED)
                .filter(PacketType::isSupported)
                .toList();
    }

    /**
     * Returns the recorded null for the protocol lib chat interceptor.
     *
     * @param command the command supplied to this operation
     * @return the result described above
     */
    static String extractPrivateMessageBody(String command) {
        // Bound regex work before vanilla validates an intercepted command packet.
        if (command == null || command.length() > 512) {
            return null;
        }
        Matcher matcher = PRIVATE_MESSAGE_COMMAND.matcher(command);
        return matcher.matches() ? matcher.group(1) : null;
    }

    /**
     * Reads message from the input used by the protocol lib chat interceptor.
     *
     * @param event the event supplied to this operation
     * @return the result described above
     */
    private static String readMessage(PacketEvent event) {
        try {
            PacketContainer packet = event.getPacket();
            return packet.getStrings().read(0);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
