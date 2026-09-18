package dev.krypt04mcg.relay;

import java.util.ArrayDeque;

/** A bounded handoff from network threads to the server tick; close is terminal. */
final class BoundedInbox<T> {
    private final ArrayDeque<T> queue = new ArrayDeque<>();
    private final int capacity;
    private boolean closed;

    BoundedInbox(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    synchronized boolean offer(T value) {
        if (closed || queue.size() >= capacity) return false;
        return queue.offer(value);
    }

    synchronized T poll() {
        return queue.poll();
    }

    synchronized void close() {
        closed = true;
        queue.clear();
    }
}
