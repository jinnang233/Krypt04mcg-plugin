package dev.krypt04mcg.relay;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FragmentOutboxTest {
    @Test void eachPollSendsOnlyOneFragmentAndPreservesTargetAndMessageOrder() {
        var queue = new FragmentOutbox<String>();
        assertTrue(queue.offer("Alice", List.of("Bob", "Alice"), List.of("first", "second"), 0));
        assertTrue(queue.offer("Carol", List.of("Bob"), List.of("third"), 0));
        assertEquals(new FragmentOutbox.Forward<>("Alice", "Bob", "first"), queue.poll(0));
        assertEquals(new FragmentOutbox.Forward<>("Alice", "Alice", "first"), queue.poll(0));
        assertEquals(new FragmentOutbox.Forward<>("Alice", "Bob", "second"), queue.poll(0));
        assertEquals(new FragmentOutbox.Forward<>("Alice", "Alice", "second"), queue.poll(0));
        assertEquals(new FragmentOutbox.Forward<>("Carol", "Bob", "third"), queue.poll(0));
        assertNull(queue.poll(0));
    }

    @Test void boundsMessagesAndReleasesCapacityAfterDelivery() {
        var queue = new FragmentOutbox<String>();
        for (int i = 0; i < 64; i++) assertTrue(queue.offer("a", List.of("b"), List.of("x"), 0));
        assertFalse(queue.offer("a", List.of("b"), List.of("x"), 0));
        assertNotNull(queue.poll(0));
        assertTrue(queue.offer("a", List.of("b"), List.of("x"), 0));
    }

    @Test void boundsTextEvenWhenMessageLimitIsNotReached() {
        var queue = new FragmentOutbox<String>();
        var large = Collections.nCopies(1024, "x".repeat(256));
        for (int i = 0; i < 4; i++) assertTrue(queue.offer("a", List.of("b"), large, 0));
        assertFalse(queue.offer("a", List.of("b"), List.of("x"), 0));
        for (int i = 0; i < 1024; i++) assertNotNull(queue.poll(0));
        assertTrue(queue.offer("a", List.of("b"), large, 0));
    }

    @Test void stalledDeliveryExpiresAndDoesNotHoldCapacity() {
        var queue = new FragmentOutbox<String>();
        assertTrue(queue.offer("a", List.of("b"), List.of("x", "y"), 0));
        assertNotNull(queue.poll(9_000_000_000L));
        assertNull(queue.poll(10_000_000_000L));
        assertTrue(queue.offer("a", List.of("b"), List.of("z"), 10_000_000_000L));
        assertEquals("z", queue.poll(10_000_000_000L).fragment());
    }

    @Test void disconnectAndShutdownReleaseDeliveries() {
        var queue = new FragmentOutbox<String>();
        queue.offer("a", List.of("b"), List.of("x"), 0);
        queue.offer("c", List.of("d"), List.of("y"), 0);
        queue.removeParticipant("b");
        assertEquals("y", queue.poll(0).fragment());
        queue.offer("a", List.of("b"), List.of("x"), 0);
        queue.removeParticipant("a");
        assertNull(queue.poll(0));
        queue.offer("a", List.of("b"), List.of("x"), 0);
        queue.clear();
        assertNull(queue.poll(0));
    }
}
