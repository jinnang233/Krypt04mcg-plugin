package dev.krypt04mcg.relay;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class CustomPayloadRelay implements PluginMessageListener {
    static final String CHANNEL = "krypt04mcg:chat_fragment";

    private static final java.util.Set<String> CHANNELS = java.util.Set.of(CHANNEL, "krypt04mcg:public_key", "krypt04mcg:file_share");

    private static final int MAX_USERNAME_CHARS = 16;
    private static final int MAX_STRING_CHARS = 32767;

    private final Krypt04McgRelayPlugin plugin;
    private final RelayTrafficLimiter traffic = new RelayTrafficLimiter();

    CustomPayloadRelay(Krypt04McgRelayPlugin plugin) {
        this.plugin = plugin;
    }

    void register() {
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : CHANNELS) {
            messenger.registerIncomingPluginChannel(plugin, channel, this);
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
    }

    void unregister() {
        traffic.clear();
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : CHANNELS) {
            messenger.unregisterIncomingPluginChannel(plugin, channel, this);
            messenger.unregisterOutgoingPluginChannel(plugin, channel);
        }
    }

    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] message) {
        if (!CHANNELS.contains(channel)) {
            return;
        }
        long now = System.nanoTime() / 1000000;
        if (!traffic.receive(source.getUniqueId(), message.length, now)) return;

        try {
            ServerboundPayload payload = ServerboundPayload.decode(message);
            if (!CHANNEL.equals(channel) && (payload.version() != 1 || payload.fragment().length() > 12100)) {
                return;
            }
            if (channel.equals("krypt04mcg:public_key") && payload.receiver().equals("*")) {
                byte[] outgoing = encodeClientbound(source.getName(), payload.fragment(), payload.version());
                for (Player target : plugin.getServer().getOnlinePlayers()) {
                    if (!target.equals(source) && target.getListeningPluginChannels().contains(channel)) {
                        if (!traffic.forward(source.getUniqueId(), outgoing.length, now)) break;
                        target.sendPluginMessage(plugin, channel, outgoing);
                    }
                }
                return;
            }
            Player receiver = plugin.getServer().getPlayerExact(payload.receiver());
            if (receiver == null || !receiver.isOnline()) {
                plugin.getLogger().fine("Custom payload receiver offline: " + payload.receiver());
                return;
            }

            if (!receiver.getListeningPluginChannels().contains(channel)) {
                return;
            }

            // Never forward the client-supplied first field. The clientbound
            // sender identity must come from Bukkit's authenticated connection.
            byte[] outgoing = encodeClientbound(source.getName(), payload.fragment(), payload.version());
            if (!traffic.forward(source.getUniqueId(), outgoing.length, now)) return;
            receiver.sendPluginMessage(plugin, channel, outgoing);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().fine("Rejected malformed " + channel + " payload from " + source.getName()
                    + ": " + e.getMessage());
        }
    }

    static byte[] encodeClientbound(String sender, String fragment, int version) {
        if (version <= 0) {
            throw new IllegalArgumentException("Invalid protocol version");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUtf(output, sender, MAX_USERNAME_CHARS);
        writeUtf(output, fragment, MAX_STRING_CHARS);
        writeVarInt(output, version);
        return output.toByteArray();
    }

    private static void writeUtf(ByteArrayOutputStream output, String value, int maxChars) {
        if (value.length() > maxChars) {
            throw new IllegalArgumentException("String is too long");
        }

        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxChars * 3) {
            throw new IllegalArgumentException("String is too long");
        }

        writeVarInt(output, bytes.length);
        output.write(bytes, 0, bytes.length);
    }

    private static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    record ServerboundPayload(String receiver, String fragment, int version) {
        static ServerboundPayload decode(byte[] data) {
            PayloadReader reader = new PayloadReader(data);
            String receiver = reader.readUtf(MAX_USERNAME_CHARS);
            String fragment = reader.readUtf(MAX_STRING_CHARS);
            int version = reader.readVarInt();

            if (!reader.finished() || version <= 0) {
                throw new IllegalArgumentException("Invalid payload");
            }

            return new ServerboundPayload(receiver, fragment, version);
        }
    }

    private static final class PayloadReader {
        private final byte[] data;
        private int index;

        private PayloadReader(byte[] data) {
            this.data = data;
        }

        private String readUtf(int maxChars) {
            int byteLength = readVarInt();
            if (byteLength < 0 || byteLength > maxChars * 3 || byteLength > data.length - index) {
                throw new IllegalArgumentException("Invalid UTF-8 length");
            }

            String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(data, index, byteLength)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                throw new IllegalArgumentException("Invalid UTF-8", e);
            }
            index += byteLength;
            if (value.length() > maxChars) {
                throw new IllegalArgumentException("String is too long");
            }
            return value;
        }

        private int readVarInt() {
            int result = 0;
            int shift = 0;

            while (true) {
                if (index >= data.length || shift >= 35) {
                    throw new IllegalArgumentException("Invalid VarInt");
                }

                int current = data[index++] & 0xFF;
                if (shift == 28 && (current & 0xF0) != 0) throw new IllegalArgumentException("VarInt overflow");
                result |= (current & 0x7F) << shift;
                if ((current & 0x80) == 0) {
                    return result;
                }
                shift += 7;
            }
        }

        private boolean finished() {
            return index == data.length;
        }
    }
}

