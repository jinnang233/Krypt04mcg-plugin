package dev.krypt04mcg.relay.fragment;

import dev.krypt04mcg.relay.model.Fragment;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class FragmentCollectorTest {
    @Test void globalMemoryBudgetRejectsGrowthAndIsReleasedOnQuitAndClear() {
        var bounded = new FragmentCollector(Duration.ofSeconds(5), 32, 1024, clock::get);
        String text = "A".repeat(256);
        for (int message = 0; message < 16; message++) {
            for (int index = 0; index < 512; index++) {
                bounded.accept(sender, new Fragment(Integer.toString(message), index, 1024, text), text);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> bounded.accept(sender,
                new Fragment("overflow", 0, 1024, text), text));
        bounded.removeSender(sender);
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

    @Test void capacityEvictsOldestAndQuitReleasesState() {
        UUID second = UUID.randomUUID(), third = UUID.randomUUID();
        add(sender, ID, 0);
        add(second, ID, 0);
        add(third, ID, 0);
        // Oldest was evicted, so its final fragment cannot complete a packet.
        assertTrue(collector.accept(sender, new Fragment(ID, 1, 2, "YQ"), "fragment").isEmpty());
        collector.removeSender(sender);
        collector.removeSender(third);
        clock.set(5_000_000_000L);
        assertEquals(0, collector.cleanupTimedOut());
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
