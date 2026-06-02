package dev.krypt04mcg.relay;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

final class ProtocolLibChatInterceptor {
    private final Krypt04McgRelayPlugin plugin;
    private final RelayConfig config;
    private final EncryptedChatRelay relay;

    ProtocolLibChatInterceptor(Krypt04McgRelayPlugin plugin, RelayConfig config, EncryptedChatRelay relay) {
        this.plugin = plugin;
        this.config = config;
        this.relay = relay;
    }

    void register() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(plugin, ListenerPriority.LOWEST,
                PacketType.Play.Client.CHAT) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                String message = readMessage(event);
                if (message == null || config.kickKrypt04McgChatSpam()) {
                    return;
                }

                Player sender = event.getPlayer();
                if (!relay.isKrypt04McgMessage(message)) {
                    return;
                }

                event.setCancelled(true);
                Bukkit.getScheduler().runTask(plugin, () -> relay.handleKrypt04McgMessage(sender, message));
            }
        });
    }

    void unregister() {
        ProtocolLibrary.getProtocolManager().removePacketListeners(plugin);
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
