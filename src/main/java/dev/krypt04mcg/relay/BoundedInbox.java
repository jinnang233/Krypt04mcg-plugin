package dev.krypt04mcg.relay;

import java.util.ArrayDeque;

/** A bounded handoff from network threads to the server tick; close is terminal. */
final class BoundedInbox<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final int capacity;
    private boolean closed;

    /**
     * Creates a bounded inbox with the supplied dependencies and initial state.
     *
     * @param capacity the capacity supplied to this operation
     */
    BoundedInbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * Returns the recorded false for the bounded cross-thread relay inbox.
     *
     * @param value the value supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    synchronized boolean offer(T value) {
        if (closed || queue.size() >= capacity) return false;
        return queue.offer(value);
    }

    /**
     * Performs the poll operation for the bounded cross-thread relay inbox.
     *
     * @return the result described above
     */
    synchronized T poll() {
        return queue.poll();
    }

    /**
     * Closes retained resources in the bounded cross-thread relay inbox.
     */
    synchronized void close() {
        closed = true;
        queue.clear();
    }
}
