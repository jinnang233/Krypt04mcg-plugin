package dev.krypt04mcg.relay.fragment;

import dev.krypt04mcg.relay.model.Fragment;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FragmentCollectorTest {
    @Test void onePlayerCannotConsumeAllGlobalAssemblySlots() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 128, 2, clock::get);
        UUID victim = UUID.randomUUID();
        for (int i = 0; i < 128; i++) bounded.accept(sender, new Fragment(String.format("%032x", i), 0, 2, "AQ"), "flood");
        bounded.accept(victim, new Fragment(ID, 0, 2, "AQ"), "first");
        assertArrayEquals(new byte[]{1, 2, 3}, bounded.accept(victim,
                new Fragment(ID, 1, 2, "ID"), "last").orElseThrow().packetBytes());
        clock.set(5_000_000_000L);
        assertEquals(16, bounded.cleanupTimedOut());
    }

    @Test void bufferedTextCanExceedFormerPerPlayerAndGlobalQuotas() {
        var bounded = new FragmentCollector(Duration.ofSeconds(120), 128, 2048, clock::get);
        String text = "A".repeat(180);
        var parser = new FragmentService();
        int bufferedText = 0;
        for (int message = 0; message < 8; message++) {
            for (int index = 0; index < 1280; index++) {
                String line = FragmentService.PREFIX + " " + String.format("%032x", message)
                        + " " + index + " 2048 " + text;
                bounded.accept(sender, parser.parse(line), line);
                bufferedText += text.length() + line.length();
            }
        }
        assertTrue(bufferedText > 4 * 1024 * 1024);
        clock.set(120_000_000_000L);
        assertEquals(8, bounded.cleanupTimedOut());
        assertArrayEquals(new byte[]{97}, bounded.accept(sender,
                new Fragment(ID, 0, 1, "YQ"), "fragment").orElseThrow().packetBytes());
    }

    @Test void maximumPacketRoundTripsAtDefaultFragmentSizeAndPacing() {
        var bounded = new FragmentCollector(Duration.ofSeconds(120), 128, 2048, clock::get);
        byte[] packet = new byte[FragmentCollector.MAX_PACKET_BYTES];
        new java.util.Random(42).nextBytes(packet);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(packet);
        int total = (encoded.length() + 179) / 180;
        var parser = new FragmentService();
        java.util.Optional<FragmentCollector.CompleteMessage> completed = java.util.Optional.empty();
        for (int index = 0; index < total; index++) {
            clock.set(index * 50_000_000L);
            String line = FragmentService.PREFIX + " " + ID + " " + index + " " + total + " "
                    + encoded.substring(index * 180, Math.min(encoded.length(), (index + 1) * 180));
            completed = bounded.accept(sender, parser.parse(line), line);
        }
        assertArrayEquals(packet, completed.orElseThrow().packetBytes());
        assertEquals(total, completed.orElseThrow().fragmentsInOrder().size());
        assertTrue(clock.get() < 120_000_000_000L);
    }

    @Test void completingOrRemovingAPlayerReleasesTheirQuota() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 128, 2, clock::get);
        for (int i = 0; i < 16; i++) bounded.accept(sender, new Fragment(Integer.toString(i), 0, 2, "AQ"), "first");
        bounded.accept(sender, new Fragment("overflow", 0, 2, "AQ"), "blocked");
        assertArrayEquals(new byte[]{1, 2, 3}, bounded.accept(sender,
                new Fragment("0", 1, 2, "ID"), "last").orElseThrow().packetBytes());
        bounded.accept(sender, new Fragment("overflow", 0, 2, "AQ"), "first");
        assertArrayEquals(new byte[]{1, 2, 3}, bounded.accept(sender,
                new Fragment("overflow", 1, 2, "ID"), "last").orElseThrow().packetBytes());
        bounded.removeSender(sender);
        bounded.accept(sender, new Fragment(ID, 0, 2, "AQ"), "first");
        clock.set(5_000_000_000L);
        assertEquals(1, bounded.cleanupTimedOut());
        assertArrayEquals(new byte[]{97}, bounded.accept(sender,
                new Fragment(ID, 0, 1, "YQ"), "fragment").orElseThrow().packetBytes());
    }

    @Test void hexCaseAliasesDoNotSplitAnAssembly() {
        collector.accept(sender, new Fragment(ID, 0, 2, "AQ"), "first");
        assertArrayEquals(new byte[]{1, 2, 3}, collector.accept(sender,
                new Fragment(ID.toUpperCase(java.util.Locale.ROOT), 1, 2, "ID"), "last").orElseThrow().packetBytes());
        clock.set(5_000_000_000L);
        assertEquals(0, collector.cleanupTimedOut());
    }

    @Test void oversizedPacketReleasesOnlyItsOwnAssembly() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 128, 2048, clock::get);
        UUID victim = UUID.randomUUID();
        bounded.accept(victim, new Fragment(ID, 0, 2, "AQ"), "first");
        String text = "A".repeat(256);
        for (int index = 0; index < 1365; index++) bounded.accept(sender,
                new Fragment(ID, index, 2048, text), text);
        assertThrows(IllegalArgumentException.class,
                () -> bounded.accept(sender, new Fragment(ID, 1365, 2048, text), text));
        assertArrayEquals(new byte[]{1, 2, 3}, bounded.accept(victim,
                new Fragment(ID, 1, 2, "ID"), "last").orElseThrow().packetBytes());
        clock.set(5_000_000_000L);
        assertEquals(0, bounded.cleanupTimedOut());
    }

    @Test void decodedPacketOneByteOverLimitDoesNotLeavePendingState() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 128, 2048, clock::get);
        String encoded = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(new byte[FragmentCollector.MAX_PACKET_BYTES + 1]);
        int total = (encoded.length() + 179) / 180;
        for (int index = 0; index < total - 1; index++) {
            String payload = encoded.substring(index * 180, (index + 1) * 180);
            bounded.accept(sender, new Fragment(ID, index, total, payload), payload);
        }
        String last = encoded.substring((total - 1) * 180);
        assertThrows(IllegalArgumentException.class,
                () -> bounded.accept(sender, new Fragment(ID, total - 1, total, last), last));
        clock.set(5_000_000_000L);
        assertEquals(0, bounded.cleanupTimedOut());
    }

    @Test void invalidCompletedEncodingDoesNotLeavePendingState() {
        assertThrows(IllegalArgumentException.class, () -> collector.accept(sender,
                new Fragment(ID, 0, 1, "A"), "fragment"));
        clock.set(5_000_000_000L);
        assertEquals(0, collector.cleanupTimedOut());
    }

    private static final String ID = "00112233445566778899aabbccddeeff";
    private final AtomicLong clock = new AtomicLong();
    private final UUID sender = UUID.randomUUID();
    private final FragmentCollector collector = new FragmentCollector(Duration.ofSeconds(5), 2, 2, clock::get);

    private void add(UUID source, String id, int index) {
        collector.accept(source, new Fragment(id, index, 2, "YQ"), "fragment");
    }

    @Test void expiresWithoutMoreTrafficAndDuplicatesCannotKeepStateAlive() {
        add(sender, ID, 0);
        clock.set(4_000_000_000L);
        add(sender, ID, 0);
        clock.set(5_000_000_000L);
        assertEquals(1, collector.cleanupTimedOut());
        assertEquals(0, collector.cleanupTimedOut());
    }

    @Test void capacityRejectsNewMessageFloodAndPreservesAdmittedMessages() {
        UUID second = UUID.randomUUID(), third = UUID.randomUUID();
        collector.accept(sender, new Fragment(ID, 0, 2, "AQ"), "first");
        collector.accept(second, new Fragment(ID, 0, 2, "AQ"), "first");
        for (int i = 0; i < 100; i++) {
            assertTrue(collector.accept(third, new Fragment(String.format("%032x", i), 0, 2, "AQ"),
                    "flood").isEmpty());
        }
        for (UUID admitted : new UUID[]{sender, second}) {
            var completed = collector.accept(admitted, new Fragment(ID, 1, 2, "ID"), "last").orElseThrow();
            assertArrayEquals(new byte[]{1, 2, 3}, completed.packetBytes());
            assertEquals(java.util.List.of("first", "last"), completed.fragmentsInOrder());
        }
        clock.set(5_000_000_000L);
        assertEquals(0, collector.cleanupTimedOut());
    }

    @Test void quitReleasesCapacityForPreviouslyRejectedMessage() {
        UUID second = UUID.randomUUID(), third = UUID.randomUUID();
        add(sender, ID, 0);
        add(second, ID, 0);
        add(third, ID, 0);
        collector.removeSender(sender);
        add(third, ID, 0);
        clock.set(5_000_000_000L);
        assertEquals(2, collector.cleanupTimedOut());
    }

    @Test void rejectedMessagesDoNotExtendDeadlinesAndExpiredSlotsCanBeReused() {
        UUID second = UUID.randomUUID(), third = UUID.randomUUID();
        add(sender, ID, 0);
        add(second, ID, 0);
        clock.set(4_000_000_000L);
        add(third, ID, 0);
        clock.set(5_000_000_000L);
        assertEquals(2, collector.cleanupTimedOut());
        assertArrayEquals(new byte[]{97}, collector.accept(third,
                new Fragment(ID, 0, 1, "YQ"), "fragment").orElseThrow().packetBytes());
    }

    @Test void validatesDirectCallersAndUnsafeLimits() {
        assertThrows(IllegalArgumentException.class, () -> collector.accept(sender,
                new Fragment(ID, -1, 2, "YQ"), "fragment"));
        assertThrows(IllegalArgumentException.class, () -> collector.accept(sender,
                new Fragment(ID, 0, 2, "x".repeat(257)), "fragment"));
        assertThrows(IllegalArgumentException.class, () -> new FragmentCollector(Duration.ofSeconds(Long.MAX_VALUE), 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new FragmentCollector(Duration.ofSeconds(5), 0, 2));
    }
}
