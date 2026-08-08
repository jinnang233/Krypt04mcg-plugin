package dev.krypt04mcg.relay;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class CustomPayloadRelay implements PluginMessageListener {
    static final String CHANNEL = "krypt04mcg:chat_fragment";

    private static final int MAX_VARINT_BYTES = 5;
    private static final int MAX_STRING_BYTES = 32767;

    private final Krypt04McgRelayPlugin plugin;

    CustomPayloadRelay(Krypt04McgRelayPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player sender, byte[] message) {
        if (!CHANNEL.equals(channel)) {
            return;
        }

        try {
            ChatFragmentPayload payload = ChatFragmentPayload.decode(message);
            Player receiver = Bukkit.getPlayerExact(payload.receiver());
            if (receiver == null) {
                plugin.getLogger().fine("Custom payload receiver offline: " + payload.receiver());
                return;
            }

            receiver.sendPluginMessage(plugin, CHANNEL, message);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Rejected malformed " + CHANNEL + " payload from " + sender.getName()
                    + ": " + e.getMessage());
        }
    }

    private record ChatFragmentPayload(String receiver, String fragment, int version) {
        static ChatFragmentPayload decode(byte[] data) {
            try {
                ByteArrayInputStream in = new ByteArrayInputStream(data);
                String receiver = readString(in);
                String fragment = readString(in);
                int version = readVarInt(in);
                if (in.available() != 0) {
                    throw new IOException("trailing payload bytes: " + in.available());
                }
                return new ChatFragmentPayload(receiver, fragment, version);
            } catch (IOException e) {
                throw new IllegalArgumentException(e.getMessage(), e);
            }
        }

        private static String readString(ByteArrayInputStream in) throws IOException {
            int length = readVarInt(in);
            if (length < 0) {
                throw new IOException("negative string length");
            }
            if (length > MAX_STRING_BYTES) {
                throw new IOException("string field too long: " + length);
            }
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) {
                throw new IOException("truncated string field");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private static int readVarInt(ByteArrayInputStream in) throws IOException {
            int value = 0;
            int position = 0;
            for (int i = 0; i < MAX_VARINT_BYTES; i++) {
                int currentByte = in.read();
                if (currentByte == -1) {
                    throw new IOException("truncated varint");
                }
                value |= (currentByte & 0x7F) << position;
                if ((currentByte & 0x80) == 0) {
                    return value;
                }
                position += 7;
            }
            throw new IOException("varint too long");
        }
    }
}
