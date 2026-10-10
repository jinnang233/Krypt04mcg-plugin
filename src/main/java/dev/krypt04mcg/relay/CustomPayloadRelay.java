package dev.krypt04mcg.relay;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

final class CustomPayloadRelay implements PluginMessageListener {
    static final String CHANNEL = "krypt04mcg:chat_fragment";
    static final String DATA_CHANNEL = "krypt04mcg:data";
    static final String TUNNEL_CHANNEL = "krypt04mcg:tunnel";
    private static final int MAX_TUNNEL_BYTES = 24 * 1024;

    private static final java.util.Set<String> CHANNELS = java.util.Set.of(CHANNEL, "krypt04mcg:public_key", "krypt04mcg:file_share", DATA_CHANNEL, TUNNEL_CHANNEL);

    private static final int MAX_USERNAME_CHARS = 16;
    private static final int MAX_STRING_CHARS = 32767;
    private static final int MAX_OPTIONAL_FRAGMENT_CHARS = 12100;

    private final Plugin plugin;
    private final RelayTrafficLimiter traffic = new RelayTrafficLimiter();

    /**
     * Creates a custom payload relay with the supplied dependencies and initial state.
     *
     * @param plugin the plugin supplied to this operation
     */
    CustomPayloadRelay(Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers the supported callbacks and channels for the legacy custom-payload relay.
     */
    void register() {
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : CHANNELS) {
            messenger.registerIncomingPluginChannel(plugin, channel, this);
            messenger.registerOutgoingPluginChannel(plugin, channel);
        }
    }

    /**
     * Removes the registered callbacks and channels from the legacy custom-payload relay.
     */
    void unregister() {
        traffic.clear();
        Messenger messenger = plugin.getServer().getMessenger();
        for (String channel : CHANNELS) {
            messenger.unregisterIncomingPluginChannel(plugin, channel, this);
            messenger.unregisterOutgoingPluginChannel(plugin, channel);
        }
    }

    /**
     * Dispatches supported legacy chat/key/file/tunnel payloads after channel-specific wire and traffic
     * checks. Forwarded sender metadata is derived from the authenticated Bukkit player rather than a
     * caller-supplied name; end-to-end cryptographic verification remains client-side.
     *
     * @param channel the business or transport channel identifier
     * @param source the source supplied to this operation
     * @param message the message supplied to this operation
     */
    @Override
    public void onPluginMessageReceived(String channel, Player source, byte[] message) {
        if (!CHANNELS.contains(channel)) {
            return;
        }
        long now = System.nanoTime() / 1000000;
        // API transfers are paced by the clients/transport. Dropping a tunnel frame
        // breaks its ordered stream; these channels must not consume legacy quotas.
        boolean api = DATA_CHANNEL.equals(channel) || TUNNEL_CHANNEL.equals(channel);
        if (message.length > 100000 || (!api && !traffic.receive(source.getUniqueId(), message.length, now))) return;

        try {
            if (TUNNEL_CHANNEL.equals(channel)) {
                relayTunnel(source, message);
                return;
            }
            ServerboundPayload payload = ServerboundPayload.decode(message,
                    CHANNEL.equals(channel) ? MAX_STRING_CHARS : MAX_OPTIONAL_FRAGMENT_CHARS);
            if (!CHANNEL.equals(channel) && payload.version() != 1) {
                return;
            }
            if (channel.equals("krypt04mcg:public_key") && payload.receiver().equals("*")) {
                byte[] outgoing = encodeClientbound(source.getName(), payload.fragment(), payload.version());
                for (Player target : plugin.getServer().getOnlinePlayers()) {
                    // Charge even non-subscribers: scanning them is still work on the server tick.
                    if (!traffic.visitBroadcastRecipient(source.getUniqueId(), now)) break;
                    if (!target.equals(source) && target.getListeningPluginChannels().contains(channel)) {
                        if (!traffic.forward(source.getUniqueId(), outgoing.length, now)) break;
                        target.sendPluginMessage(plugin, channel, outgoing);
                    }
                }
                return;
            }
            // Data envelopes are opaque: no business-channel parsing, chat prefix,
            // file validation or file/chat subscription dependency. Only public keys broadcast.
            Player receiver = plugin.getServer().getPlayerExact(payload.receiver());
            if (receiver == null || !receiver.isOnline()) {
                plugin.getLogger().fine(() -> "Custom payload receiver offline: " + safeLogText(payload.receiver()));
                return;
            }

            if (!receiver.getListeningPluginChannels().contains(channel)) {
                return;
            }

            // Never forward the client-supplied first field. The clientbound
            // sender identity must come from Bukkit's authenticated connection.
            byte[] outgoing = encodeClientbound(source.getName(), payload.fragment(), payload.version());
            if (!api && !traffic.forward(source.getUniqueId(), outgoing.length, now)) return;
            receiver.sendPluginMessage(plugin, channel, outgoing);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().fine(() -> "Rejected malformed " + channel + " payload from " + source.getName()
                    + ": " + e.getMessage());
        }
    }

    /**
     * Performs the relay tunnel operation for the legacy custom-payload relay.
     *
     * @param source the source supplied to this operation
     * @param message the message supplied to this operation
     */
    private void relayTunnel(Player source, byte[] message) {
        PayloadReader reader = new PayloadReader(message);
        String peer = reader.readUtf(MAX_USERNAME_CHARS);
        int length = reader.readVarInt();
        if (length < 0 || length > MAX_TUNNEL_BYTES || length != message.length - reader.index) {
            throw new IllegalArgumentException("Invalid tunnel envelope length");
        }
        // No broadcast or envelope inspection: authentication remains end-to-end.
        Player receiver = plugin.getServer().getPlayerExact(peer);
        if (receiver == null || !receiver.isOnline()
                || !receiver.getListeningPluginChannels().contains(TUNNEL_CHANNEL)) return;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        writeUtf(output, source.getName(), MAX_USERNAME_CHARS);
        writeVarInt(output, length);
        output.write(message, reader.index, length);
        byte[] outgoing = output.toByteArray();
        receiver.sendPluginMessage(plugin, TUNNEL_CHANNEL, outgoing);
    }

    /**
     * Performs the safe log text operation for the legacy custom-payload relay.
     *
     * @param value the value supplied to this operation
     * @return the result described above
     */
    private static String safeLogText(String value) {
        return value.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ");
    }

    /**
     * Serializes forwarded sender identity, opaque fragment contents and protocol version. The
     * server-authenticated sender label supports client identity binding but does not substitute for
     * signature or AEAD verification.
     *
     * @param sender the sender or source associated with this operation
     * @param fragment the individual fragment or delivery record
     * @param version the version supplied to this operation
     * @return the resulting array produced by this operation
     */
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

    /**
     * Writes utf to the output used by the legacy custom-payload relay.
     *
     * @param output the destination buffer or stream for produced data
     * @param value the value supplied to this operation
     * @param maxChars the max chars supplied to this operation
     */
    static void writeUtf(ByteArrayOutputStream output, String value, int maxChars) {
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

    /**
     * Writes var int to the output used by the legacy custom-payload relay.
     *
     * @param output the destination buffer or stream for produced data
     * @param value the value supplied to this operation
     */
    static void writeVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7F) != 0) {
            output.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    record ServerboundPayload(String receiver, String fragment, int version) {
        /**
         * Decodes the supplied input using the format expected by the legacy custom-payload relay.
         *
         * @param data the data supplied to this operation
         * @return the result described above
         */
        static ServerboundPayload decode(byte[] data) {
            return decode(data, MAX_STRING_CHARS);
        }

        /**
         * Decodes the supplied input using the format expected by the legacy custom-payload relay.
         *
         * @param data the data supplied to this operation
         * @param maxFragmentChars the max fragment chars supplied to this operation
         * @return the result described above
         */
        static ServerboundPayload decode(byte[] data, int maxFragmentChars) {
            PayloadReader reader = new PayloadReader(data);
            String receiver = reader.readUtf(MAX_USERNAME_CHARS);
            String fragment = reader.readUtf(maxFragmentChars);
            int version = reader.readVarInt();

            if (!reader.finished() || version <= 0) {
                throw new IllegalArgumentException("Invalid payload");
            }

            return new ServerboundPayload(receiver, fragment, version);
        }
    }

    static final class PayloadReader {
        private final byte[] data;
        private int index;

        /**
         * Creates a payload reader with the supplied dependencies and initial state.
         *
         * @param data the data supplied to this operation
         */
        PayloadReader(byte[] data) {
            this.data = data;
        }

        /**
         * Reads utf from the input used by the legacy custom-payload relay.
         *
         * @param maxChars the max chars supplied to this operation
         * @return the result described above
         */
        String readUtf(int maxChars) {
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

        /**
         * Reads var int from the input used by the legacy custom-payload relay.
         *
         * @return the result described above
         */
        int readVarInt() {
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

        /**
         * Reads bytes from the input used by the legacy custom-payload relay.
         *
         * @param length the requested or declared byte count
         * @return the resulting array produced by this operation
         */
        byte[] readBytes(int length) {
            if (length < 0 || length > data.length - index) throw new IllegalArgumentException("Invalid byte length");
            byte[] bytes = java.util.Arrays.copyOfRange(data, index, index + length);
            index += length;
            return bytes;
        }

        /**
         * Performs the finished operation for the legacy custom-payload relay.
         *
         * @return whether the condition or operation described above succeeds
         */
        boolean finished() {
            return index == data.length;
        }
    }
}
