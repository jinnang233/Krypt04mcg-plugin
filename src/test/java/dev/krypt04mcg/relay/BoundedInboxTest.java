package dev.krypt04mcg.relay;

import org.junit.jupiter.api.Test;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BoundedInboxTest {
    @Test void concurrentFloodIsBoundedAndClosePreventsRepopulation() throws Exception {
        var inbox = new BoundedInbox<Integer>(1024);
        var accepted = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(4)) {
            for (int worker = 0; worker < 4; worker++) {
                workers.submit(() -> {
                    for (int i = 0; i < 10000; i++) {
                        if (inbox.offer(i)) accepted.incrementAndGet();
                    }
                });
            }
        }
        assertEquals(1024, accepted.get());
        inbox.close();
        assertNull(inbox.poll());
        assertFalse(inbox.offer(1));
    }

    @Test void preservesOrderAndRecoversCapacityAfterDrain() {
        var inbox = new BoundedInbox<Integer>(2);
        assertTrue(inbox.offer(1));
        assertTrue(inbox.offer(2));
        assertFalse(inbox.offer(3));
        assertEquals(1, inbox.poll());
        assertTrue(inbox.offer(3));
        assertEquals(2, inbox.poll());
        assertEquals(3, inbox.poll());
        assertNull(inbox.poll());
    }
}
