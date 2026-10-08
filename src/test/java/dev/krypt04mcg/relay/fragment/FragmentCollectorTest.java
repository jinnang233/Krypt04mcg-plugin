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

    @Test void perPlayerTextBudgetCannotFillTheGlobalBudget() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 128, 1024, clock::get);
        String text = "A".repeat(256);
        for (int message = 0; message < 4; message++) {
            for (int index = 0; index < 512; index++) bounded.accept(sender,
                    new Fragment(Integer.toString(message), index, 1024, text), text);
        }
        assertThrows(IllegalArgumentException.class,
                () -> bounded.accept(sender, new Fragment("overflow", 0, 1024, text), text));
        UUID other = UUID.randomUUID();
        assertArrayEquals(new byte[]{97}, bounded.accept(other,
                new Fragment(ID, 0, 1, "YQ"), "fragment").orElseThrow().packetBytes());
        bounded.removeSender(sender);
        assertTrue(bounded.accept(sender, new Fragment(ID, 0, 2, "AQ"), text).isEmpty());
        assertArrayEquals(new byte[]{1, 2, 3}, bounded.accept(sender,
                new Fragment(ID, 1, 2, "ID"), "last").orElseThrow().packetBytes());
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

    @Test void globalMemoryBudgetRejectsGrowthAndIsReleasedOnQuitAndClear() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 32, 1024, clock::get);
        String text = "A".repeat(256);
        for (int message = 0; message < 16; message++) {
            UUID source = new UUID(0, message / 4);
            for (int index = 0; index < 512; index++) {
                bounded.accept(source, new Fragment(Integer.toString(message), index, 1024, text), text);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> bounded.accept(sender,
                new Fragment("overflow", 0, 1024, text), text));
        bounded.removeSender(new UUID(0, 0));
        assertArrayEquals(new byte[]{97}, bounded.accept(sender,
                new Fragment(ID, 0, 1, "YQ"), "fragment").orElseThrow().packetBytes());
        bounded.clear();
        assertTrue(bounded.accept(sender, new Fragment(ID, 0, 2, "YQ"), "fragment").isEmpty());
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
