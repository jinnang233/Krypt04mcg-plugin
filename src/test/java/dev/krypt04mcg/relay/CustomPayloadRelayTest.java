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

    @Test void missingOfflineAndUnsubscribedTargetsAreDropped() {
        player("Offline", false, Set.of(DATA));
        player("ChatFileOnly", true, Set.of(CustomPayloadRelay.CHANNEL, "krypt04mcg:file_share"));
        player("Carol", true, Set.of(DATA));
        for (String target : List.of("Missing", "Offline", "ChatFileOnly", "*")) {
            relay.onPluginMessageReceived(DATA, source, packet(target, "opaque", 1));
        }
        assertTrue(deliveries.isEmpty());
    }

    @Test void acceptsMaximumMinecraftUtfLength() {
        player("Bob", true, Set.of(DATA));
        String fragment = "界".repeat(12100);
        relay.onPluginMessageReceived(DATA, source, packet("Bob", fragment, 1));
        assertEquals(1, deliveries.size());
        assertArrayEquals(packet("Alice", fragment, 1), deliveries.getFirst().bytes());
    }

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

    @Test void oversizedIngressIsDropped() {
        player("Bob", true, Set.of(DATA));
        relay.onPluginMessageReceived(DATA, source, new byte[100001]);
        assertTrue(deliveries.isEmpty());
    }

    @Test void registersAndUnregistersDataWithExistingChannels() {
        Set<String> expected = Set.of(DATA, CustomPayloadRelay.CHANNEL,
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

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    // Independent wire fixture: Minecraft UTF-8 with VarInt byte lengths, never writeUTF.
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

    private static void varInt(ByteArrayOutputStream output, int value) {
        do {
            int next = value & 127;
            value >>>= 7;
            output.write(value == 0 ? next : next | 128);
        } while (value != 0);
    }

    private record Delivery(String target, String channel, byte[] bytes) {}
}
