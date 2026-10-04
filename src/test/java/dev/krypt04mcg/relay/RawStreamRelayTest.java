package dev.krypt04mcg.relay;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

import static dev.krypt04mcg.relay.RawStreamRelay.*;
import static org.junit.jupiter.api.Assertions.*;

class RawStreamRelayTest {
    private final Map<String, Player> players = new HashMap<>();
    private final List<Delivery> deliveries = new ArrayList<>();
    private final Set<String> incoming = new HashSet<>(), outgoing = new HashSet<>();
    private boolean cancelled;
    private boolean enabled = true;
    private final Messenger messenger = proxy(Messenger.class, (p, m, a) -> {
        switch (m.getName()) {
            case "registerIncomingPluginChannel" -> incoming.add((String) a[1]);
            case "registerOutgoingPluginChannel" -> outgoing.add((String) a[1]);
            case "unregisterIncomingPluginChannel" -> incoming.remove((String) a[1]);
            case "unregisterOutgoingPluginChannel" -> outgoing.remove((String) a[1]);
            default -> throw new AssertionError(m);
        }
        return null;
    });
    private final PluginManager manager = proxy(PluginManager.class, (p, m, a) -> {
        assertEquals("registerEvents", m.getName()); return null;
    });
    private final BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> {
        assertEquals("runTaskTimer", m.getName());
        return proxy(BukkitTask.class, (t, method, args) -> {
            assertEquals("cancel", method.getName()); cancelled = true; return null;
        });
    });
    private final Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
        case "getPlayerExact" -> players.get(a[0]);
        case "getMessenger" -> messenger;
        case "getPluginManager" -> manager;
        case "getScheduler" -> scheduler;
        default -> throw new AssertionError(m);
    });
    private final Plugin plugin = proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
        case "getServer" -> server;
        case "isEnabled" -> enabled;
        case "getLogger" -> Logger.getLogger("RawStreamRelayTest");
        default -> throw new AssertionError(m);
    });
    private final RawStreamRelay relay = new RawStreamRelay(plugin, 2);
    private final Player alice = player("Alice", Set.of(CONTROL, DATA_PREFIX + 0, DATA_PREFIX + 1));
    private final Player bob = player("Bob", Set.of(CONTROL, DATA_PREFIX + 0, DATA_PREFIX + 1));
    private final UUID id = UUID.randomUUID();

    @Test void allocatesCommonSlotAndForwardsTheExactRawArrayBothWays() throws Exception {
        Player receiver = player("Bob", Set.of(CONTROL, DATA_PREFIX + 1));
        open(id);
        assertControl(0, "Alice", 2, "Bob", id, 1);
        assertControl(1, "Bob", 1, "Alice", id, 1);
        deliveries.clear();
        byte[] raw = new byte[16400]; new Random(42).nextBytes(raw);
        relay.onPluginMessageReceived(DATA_PREFIX + 1, alice, raw);
        assertTrue(deliveries.isEmpty());
        control(receiver, 3, "Alice", id, 1); deliveries.clear();
        relay.onPluginMessageReceived(DATA_PREFIX + 1, alice, raw);
        relay.onPluginMessageReceived(DATA_PREFIX + 1, receiver, raw);
        assertEquals(List.of("Bob", "Alice"), deliveries.stream().map(Delivery::target).toList());
        for (Delivery d : deliveries) {
            assertEquals(DATA_PREFIX + 1, d.channel);
            assertSame(raw, d.bytes); // No decoding, wrapping or copying.
        }
    }

    @Test void blocksUnrelatedPlayersAndForgedLifecycleMessages() throws Exception {
        Player eve = player("Eve", Set.of(CONTROL, DATA_PREFIX + 0));
        open(id); control(alice, 3, "Bob", id, 0); deliveries.clear();
        relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, new byte[16]);
        assertTrue(deliveries.isEmpty()); // Only the receiver may send READY.
        control(bob, 3, "Alice", id, 0); deliveries.clear();
        control(eve, 5, "Alice", id, 0);
        control(alice, 6, "Bob", id, 0);
        control(alice, 2, "Bob", id, 0);
        control(bob, 5, "Alice", UUID.randomUUID(), 0);
        control(bob, 5, "Eve", id, 0);
        relay.onPluginMessageReceived(DATA_PREFIX + 0, eve, new byte[16]);
        relay.onPluginMessageReceived(DATA_PREFIX + "00", alice, new byte[16]);
        assertTrue(deliveries.isEmpty());
        relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, new byte[16]);
        assertEquals(1, deliveries.size());
    }

    @Test void endIsDirectionalAndBothEndsReleaseTheSlot() throws Exception {
        open(id); control(bob, 3, "Alice", id, 0); control(alice, 4, "Bob", id, 0);
        deliveries.clear();
        relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, new byte[16]);
        assertTrue(deliveries.isEmpty());
        relay.onPluginMessageReceived(DATA_PREFIX + 0, bob, new byte[16]);
        assertEquals(1, deliveries.size());
        control(bob, 4, "Alice", id, 0); deliveries.clear();
        UUID next = UUID.randomUUID(); open(next);
        assertControl(0, "Alice", 2, "Bob", next, 0);
    }

    @Test void resetDisconnectAndIdleTimeoutReleaseRoutes() throws Exception {
        for (int action = 0; action < 3; action++) {
            open(id); deliveries.clear();
            switch (action) {
                case 0 -> control(bob, 5, "Alice", id, 0);
                case 1 -> relay.onQuit(new PlayerQuitEvent(bob, "quit"));
                case 2 -> relay.expire(System.nanoTime() / 1000000 + 60001);
            }
            assertFalse(deliveries.isEmpty());
            assertEquals(action == 0 ? 5 : 6, StreamControl.decode(deliveries.getFirst().bytes).kind().ordinal());
            deliveries.clear();
        }
        open(id); assertControl(0, "Alice", 2, "Bob", id, 0);
    }

    @Test void poolExhaustionAndMissingReceiverAbortOpen() throws Exception {
        open(id); open(UUID.randomUUID()); deliveries.clear();
        UUID third = UUID.randomUUID(); open(third);
        assertControl(0, "Alice", 6, "Bob", third, -1);
        deliveries.clear(); control(alice, 1, "Missing", third, -1);
        assertControl(0, "Alice", 6, "Missing", third, -1);
    }

    @Test void exchangePreservesBodyAndUsesAuthenticatedSource() throws Exception {
        relay.onPluginMessageReceived(CONTROL, alice, packet(0, "Bob", id, -1, new byte[30000]));
        assertArrayEquals(packet(0, "Alice", id, -1, new byte[30000]), deliveries.getFirst().bytes);
    }

    @Test void malformedControlsCannotAllocate() throws Exception {
        byte[] valid = packet(1, "Bob", id, -1, new byte[32]);
        for (int length = 0; length < valid.length; length++)
            relay.onPluginMessageReceived(CONTROL, alice, Arrays.copyOf(valid, length));
        relay.onPluginMessageReceived(CONTROL, alice, Arrays.copyOf(valid, valid.length + 1));
        relay.onPluginMessageReceived(CONTROL, alice, packet(99, "Bob", id, -1, new byte[32]));
        relay.onPluginMessageReceived(CONTROL, alice, packet(1, "Bob", id, -1, new byte[31]));
        assertTrue(deliveries.isEmpty());
    }

    @Test void arbitraryDataLengthsAndContentsForwardUnchangedWithoutAborting() throws Exception {
        open(id); control(bob, 3, "Alice", id, 0); deliveries.clear();
        Random random = new Random(42);
        for (int size : new int[]{0, 1, 15, 16, 16400, 16401, 24577, 100001}) {
            byte[] bytes = new byte[size];
            random.nextBytes(bytes);
            relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, bytes);
            relay.onPluginMessageReceived(DATA_PREFIX + 0, bob, bytes);
            assertEquals(2, deliveries.size());
            assertEquals(List.of("Bob", "Alice"), deliveries.stream().map(Delivery::target).toList());
            for (Delivery delivery : deliveries) {
                assertEquals(DATA_PREFIX + 0, delivery.channel);
                assertSame(bytes, delivery.bytes);
            }
            deliveries.clear();
        }
    }

    @Test void bulkDownloadPreservesEveryRecordAndCanCloseAndReopen() throws Exception {
        open(id); control(bob, 3, "Alice", id, 0); deliveries.clear();
        // More than the old byte and packet burst budgets, in both directions.
        byte[][] records = {new byte[16400], new byte[16399], new byte[16]};
        for (int i = 0; i < 8192; i++) {
            byte[] bytes = records[i % records.length];
            relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, bytes);
            relay.onPluginMessageReceived(DATA_PREFIX + 0, bob, bytes);
            assertEquals(2, deliveries.size());
            for (int j = 0; j < 2; j++) {
                Delivery d = deliveries.get(j);
                assertEquals(j == 0 ? "Bob" : "Alice", d.target);
                assertEquals(DATA_PREFIX + 0, d.channel);
                assertSame(bytes, d.bytes);
            }
            deliveries.clear();
        }
        control(alice, 4, "Bob", id, 0);
        assertControl(0, "Bob", 4, "Alice", id, 0);
        control(bob, 4, "Alice", id, 0);
        assertControl(1, "Alice", 4, "Bob", id, 0);
        deliveries.clear();
        UUID next = UUID.randomUUID(); open(next);
        assertControl(0, "Alice", 2, "Bob", next, 0);
    }

    @Test void downloadDoesNotExhaustControlBudgetOrInterruptAnotherStream() throws Exception {
        open(id); control(bob, 3, "Alice", id, 0); deliveries.clear();
        byte[] bytes = new byte[16400];
        for (int i = 0; i < 4096; i++) {
            relay.onPluginMessageReceived(DATA_PREFIX + 0, alice, bytes);
            assertEquals(1, deliveries.size());
            assertEquals(DATA_PREFIX + 0, deliveries.getFirst().channel);
            deliveries.clear();
        }
        UUID second = UUID.randomUUID(); open(second);
        assertControl(0, "Alice", 2, "Bob", second, 1);
        control(bob, 3, "Alice", second, 1); deliveries.clear();
        for (int slot = 0; slot < 2; slot++) {
            relay.onPluginMessageReceived(DATA_PREFIX + slot, alice, bytes);
            assertEquals(DATA_PREFIX + slot, deliveries.get(slot).channel);
            assertSame(bytes, deliveries.get(slot).bytes);
        }
        deliveries.clear();
        control(alice, 5, "Bob", second, 1);
        assertControl(0, "Bob", 5, "Alice", second, 1);
    }

    @Test void registrationHonorsCountAndReloadClearsRoutes() throws Exception {
        relay.register();
        assertEquals(Set.of(CONTROL, DATA_PREFIX + 0, DATA_PREFIX + 1), incoming);
        assertEquals(incoming, outgoing);
        open(id); deliveries.clear(); relay.unregister();
        assertTrue(cancelled);
        assertTrue(incoming.isEmpty()); assertTrue(outgoing.isEmpty());
        assertEquals(2, deliveries.size());
        relay.register(); deliveries.clear(); open(id);
        assertControl(0, "Alice", 2, "Bob", id, 0); relay.unregister();
        for (int count : new int[]{0, 300}) {
            RawStreamRelay bounded = new RawStreamRelay(plugin, count);
            bounded.register(); assertEquals(count == 0 ? 2 : 257, incoming.size()); bounded.unregister();
        }
    }

    @Test void disableCleansUpWithoutSendingFromADisabledPlugin() throws Exception {
        relay.register(); open(id); deliveries.clear();
        enabled = false;
        relay.unregister();
        assertTrue(cancelled);
        assertTrue(incoming.isEmpty()); assertTrue(outgoing.isEmpty());
        assertTrue(deliveries.isEmpty());
    }

    private void open(UUID stream) throws Exception { control(alice, 1, "Bob", stream, -1); }
    private void control(Player source, int kind, String peer, UUID stream, int slot) throws Exception {
        relay.onPluginMessageReceived(CONTROL, source, packet(kind, peer, stream, slot, new byte[32]));
    }
    private void assertControl(int index, String target, int kind, String peer, UUID stream, int slot) throws Exception {
        Delivery d = deliveries.get(index);
        assertEquals(target, d.target); assertEquals(CONTROL, d.channel);
        assertArrayEquals(packet(kind, peer, stream, slot, new byte[32]), d.bytes);
    }

    // Independent FriendlyByteBuf wire fixture; fixed integers are big endian.
    private static byte[] packet(int kind, String peer, UUID id, int slot, byte[] body) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        varInt(out, kind); utf(out, peer);
        out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); out.writeInt(slot);
        utf(out, "example:stream"); utf(out, "session"); out.writeLong(7);
        varInt(out, body.length); out.write(body); return bytes.toByteArray();
    }
    private static void utf(DataOutputStream out, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); varInt(out, bytes.length); out.write(bytes);
    }
    private static void varInt(DataOutputStream out, int value) throws Exception {
        do { int part = value & 127; value >>>= 7; out.writeByte(value == 0 ? part : part | 128); } while (value != 0);
    }
    private Player player(String name, Set<String> channels) {
        UUID uuid = UUID.randomUUID();
        Player player = proxy(Player.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> uuid;
            case "isOnline" -> true;
            case "getListeningPluginChannels" -> channels;
            case "sendPluginMessage" -> { deliveries.add(new Delivery(name, (String) a[1], (byte[]) a[2])); yield null; }
            default -> throw new AssertionError(m);
        });
        players.put(name, player); return player;
    }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private record Delivery(String target, String channel, byte[] bytes) {}
}
