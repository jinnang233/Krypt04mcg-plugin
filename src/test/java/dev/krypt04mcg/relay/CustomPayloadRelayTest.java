package dev.krypt04mcg.relay;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CustomPayloadRelayTest {
    private static final String DATA = "krypt04mcg:data";
    private static final String TUNNEL = "krypt04mcg:tunnel";
    private final Map<String, Player> players = new HashMap<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private final Set<String> incoming = new HashSet<>(), outgoing = new HashSet<>();
    private final Messenger messenger = proxy(Messenger.class, (p, method, args) -> {
        String channel = (String) args[1];
        switch (method.getName()) {
            case "registerIncomingPluginChannel" -> incoming.add(channel);
            case "registerOutgoingPluginChannel" -> outgoing.add(channel);
            case "unregisterIncomingPluginChannel" -> incoming.remove(channel);
            case "unregisterOutgoingPluginChannel" -> outgoing.remove(channel);
            default -> throw new AssertionError(method);
        }
        return null;
    });
    private final Server server = proxy(Server.class, (p, method, args) -> switch (method.getName()) {
        case "getPlayerExact" -> players.get(args[0]);
        case "getMessenger" -> messenger;
        // Any broadcast scan or access to file/chat settings fails the test.
        default -> throw new AssertionError(method);
    });
    private final Plugin plugin = proxy(Plugin.class, (p, method, args) -> switch (method.getName()) {
        case "getServer" -> server;
        case "getLogger" -> Logger.getLogger("CustomPayloadRelayTest");
        default -> throw new AssertionError(method);
    });
    private final CustomPayloadRelay relay = new CustomPayloadRelay(plugin);
    private final Player source = player("Alice", true, Set.of());

    /**
     * Verifies that forwards only to data subscriber with authenticated sender.
     */
    @Test void forwardsOnlyToDataSubscriberWithAuthenticatedSender() {
        player("Bob", true, Set.of(DATA));
        player("Carol", true, Set.of(DATA));
        // Deliberately opaque, without a chat prefix or a valid file-fragment header.
        String fragment = "opaque\u0000信封😀:unparsed";
        relay.onPluginMessageReceived(DATA, source, packet("Bob", fragment, 1));

        assertEquals(1, deliveries.size());
        Delivery delivery = deliveries.getFirst();
        assertEquals("Bob", delivery.target());
        assertEquals(DATA, delivery.channel());
        assertArrayEquals(packet("Alice", fragment, 1), delivery.bytes());
    }

    /**
     * Verifies that missing offline and unsubscribed targets are dropped.
     */
    @Test void missingOfflineAndUnsubscribedTargetsAreDropped() {
        player("Offline", false, Set.of(DATA));
        player("ChatFileOnly", true, Set.of(CustomPayloadRelay.CHANNEL, "krypt04mcg:file_share"));
        player("Carol", true, Set.of(DATA));
        for (String target : List.of("Missing", "Offline", "ChatFileOnly", "*")) {
            relay.onPluginMessageReceived(DATA, source, packet(target, "opaque", 1));
        }
        assertTrue(deliveries.isEmpty());
    }

    /**
     * Verifies that accepts maximum minecraft utf length.
     */
    @Test void acceptsMaximumMinecraftUtfLength() {
        player("Bob", true, Set.of(DATA));
        String fragment = "界".repeat(12100);
        relay.onPluginMessageReceived(DATA, source, packet("Bob", fragment, 1));
        assertEquals(1, deliveries.size());
        assertArrayEquals(packet("Alice", fragment, 1), deliveries.getFirst().bytes());
    }

    /**
     * Verifies that rejects malformed packets and unsupported versions.
     */
    @Test void rejectsMalformedPacketsAndUnsupportedVersions() {
        player("Bob", true, Set.of(DATA));
        byte[] valid = packet("Bob", "x", 1);
        List<byte[]> malformed = new ArrayList<>(List.of(
                new byte[0], Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, valid.length + 1),
                packet("B".repeat(17), "x", 1), packet("Bob", "x".repeat(12101), 1),
                packet("Bob", "界".repeat(12101), 1),
                packet("Bob", "x", 0), packet("Bob", "x", 2), packet("Bob", "x", -1),
                new byte[]{(byte) 0x80},
                new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x10},
                new byte[]{(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0}));
        byte[] invalidUtf = valid.clone();
        invalidUtf[5] = (byte) 0xff;
        malformed.add(invalidUtf);
        byte[] invalidLength = valid.clone();
        invalidLength[4] = 100;
        malformed.add(invalidLength);
        for (byte[] message : malformed) {
            assertDoesNotThrow(() -> relay.onPluginMessageReceived(DATA, source, message));
            assertTrue(deliveries.isEmpty(), Arrays.toString(message));
        }
        // Rejection does not prevent a subsequent valid packet from being delivered.
        relay.onPluginMessageReceived(DATA, source, valid);
        assertEquals(1, deliveries.size());
    }

    /**
     * Verifies that oversized ingress is dropped.
     */
    @Test void oversizedIngressIsDropped() {
        player("Bob", true, Set.of(DATA));
        relay.onPluginMessageReceived(DATA, source, new byte[100001]);
        assertTrue(deliveries.isEmpty());
    }

    /**
     * Verifies that registers and unregisters data with existing channels.
     */
    @Test void registersAndUnregistersDataWithExistingChannels() {
        Set<String> expected = Set.of(DATA, TUNNEL, CustomPayloadRelay.CHANNEL,
                "krypt04mcg:public_key", "krypt04mcg:file_share");
        relay.register();
        assertEquals(expected, incoming);
        assertEquals(expected, outgoing);
        relay.unregister();
        assertTrue(incoming.isEmpty());
        assertTrue(outgoing.isEmpty());
        relay.register();
        assertEquals(expected, incoming);
        assertEquals(expected, outgoing);
    }

    /**
     * Verifies that forwards binary tunnel unchanged with authenticated sender.
     */
    @Test void forwardsBinaryTunnelUnchangedWithAuthenticatedSender() {
        player("Bob", true, Set.of(TUNNEL));
        byte[] envelope = new byte[24 * 1024];
        new Random(42).nextBytes(envelope);
        relay.onPluginMessageReceived(TUNNEL, source, tunnel("Bob", envelope));
        assertEquals(1, deliveries.size());
        assertEquals(TUNNEL, deliveries.getFirst().channel());
        assertArrayEquals(tunnel("Alice", envelope), deliveries.getFirst().bytes());
    }

    /**
     * Verifies that rejects malformed tunnel and does not use other subscriptions.
     */
    @Test void rejectsMalformedTunnelAndDoesNotUseOtherSubscriptions() {
        player("Bob", true, Set.of(TUNNEL));
        player("DataOnly", true, Set.of(DATA));
        player("Offline", false, Set.of(TUNNEL));
        byte[] valid = tunnel("Bob", new byte[] {0, (byte) 255, 7});
        for (byte[] message : List.of(new byte[0], Arrays.copyOf(valid, valid.length - 1),
                Arrays.copyOf(valid, valid.length + 1), tunnel("Bob", new byte[24577]),
                tunnel("B".repeat(17), new byte[1]),
                new byte[] {3, 'B', 'o', 'b', (byte) 255, (byte) 255, (byte) 255, (byte) 255, 0x10},
                new byte[] {1, (byte) 255, 0}, packet("Bob", "old layout", 1))) {
            relay.onPluginMessageReceived(TUNNEL, source, message);
        }
        for (String peer : List.of("*", "Missing", "Offline", "DataOnly")) {
            relay.onPluginMessageReceived(TUNNEL, source, tunnel(peer, new byte[1]));
        }
        assertTrue(deliveries.isEmpty());
        relay.onPluginMessageReceived(TUNNEL, source, valid);
        assertEquals(1, deliveries.size());
    }

    /**
     * Provides the tunnel fixture operation used by the custom payload relay test regression scenarios.
     *
     * @param peer the peer identifier associated with this operation
     * @param envelope the envelope supplied to this operation
     * @return the resulting array produced by this operation
     */
    private static byte[] tunnel(String peer, byte[] envelope) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] name = peer.getBytes(StandardCharsets.UTF_8);
        varInt(output, name.length);
        output.writeBytes(name);
        varInt(output, envelope.length);
        output.writeBytes(envelope);
        return output.toByteArray();
    }

    /**
     * Verifies that api bursts are lossless and independent of legacy quotas.
     */
    @Test void apiBurstsAreLosslessAndIndependentOfLegacyQuotas() {
        player("Bob", true, Set.of(TUNNEL, DATA, CustomPayloadRelay.CHANNEL));
        byte[] legacy = packet("Bob", "x", 1);
        // Exhaust the old per-source packet budget before starting API traffic.
        for (int i = 0; i < 600; i++) relay.onPluginMessageReceived(CustomPayloadRelay.CHANNEL, source, legacy);
        deliveries.clear();
        byte[] binary = tunnel("Bob", new byte[24576]);
        byte[] data = packet("Bob", "x".repeat(12100), 1);
        for (int i = 0; i < 2048; i++) {
            relay.onPluginMessageReceived(TUNNEL, source, binary);
            relay.onPluginMessageReceived(DATA, source, data);
            assertEquals(2, deliveries.size(), "API packet lost at " + i);
            assertArrayEquals(tunnel("Alice", new byte[24576]), deliveries.getFirst().bytes());
            deliveries.clear();
        }
    }

    /**
     * Provides the player fixture operation used by the custom payload relay test regression scenarios.
     *
     * @param name the name supplied to this operation
     * @param online the online supplied to this operation
     * @param channels the channels supplied to this operation
     * @return the result described above
     */
    private Player player(String name, boolean online, Set<String> channels) {
        UUID id = UUID.randomUUID();
        Player player = proxy(Player.class, (p, method, args) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> online;
            case "getListeningPluginChannels" -> channels;
            case "sendPluginMessage" -> {
                assertSame(plugin, args[0]);
                deliveries.add(new Delivery(name, (String) args[1], ((byte[]) args[2]).clone()));
                yield null;
            }
            default -> throw new AssertionError(method);
        });
        players.put(name, player);
        return player;
    }

    /**
     * Provides the proxy fixture operation used by the custom payload relay test regression scenarios.
     *
     * @param type the type supplied to this operation
     * @param handler the handler supplied to this operation
     * @return the result described above
     */
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    // Independent wire fixture: Minecraft UTF-8 with VarInt byte lengths, never writeUTF.
    /**
     * Provides the packet fixture operation used by the custom payload relay test regression scenarios.
     *
     * @param peer the peer identifier associated with this operation
     * @param fragment the individual fragment or delivery record
     * @param version the version supplied to this operation
     * @return the resulting array produced by this operation
     */
    private static byte[] packet(String peer, String fragment, int version) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (String value : List.of(peer, fragment)) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            varInt(output, bytes.length);
            output.writeBytes(bytes);
        }
        varInt(output, version);
        return output.toByteArray();
    }

    /**
     * Provides the var int fixture operation used by the custom payload relay test regression scenarios.
     *
     * @param output the destination buffer or stream for produced data
     * @param value the value supplied to this operation
     */
    private static void varInt(ByteArrayOutputStream output, int value) {
        do {
            int next = value & 127;
            value >>>= 7;
            output.write(value == 0 ? next : next | 128);
        } while (value != 0);
    }

    private record Delivery(String target, String channel, byte[] bytes) {}
}
