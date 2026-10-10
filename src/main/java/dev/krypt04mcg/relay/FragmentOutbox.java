package dev.krypt04mcg.relay;

import java.util.ArrayDeque;
import java.util.List;
import dev.krypt04mcg.relay.fragment.FragmentCollector;

/** Main-thread-only delivery cursor. Bounds message count and sends one fragment per poll. */
final class FragmentOutbox<T> {
    private static final int MAX_MESSAGES = 64;
    private static final long LIFETIME_NANOS = 10_000_000_000L;
    private final ArrayDeque<Delivery<T>> deliveries = new ArrayDeque<>();

    /**
     * Returns the recorded false for the incremental relay delivery queue.
     *
     * @param sender the sender or source associated with this operation
     * @param targets the targets supplied to this operation
     * @param fragments the fragments supplied to this operation
     * @param now the now supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    boolean offer(T sender, List<T> targets, List<String> fragments, long now) {
        expire(now);
        if (deliveries.size() >= MAX_MESSAGES || targets.isEmpty() || targets.size() > 2
                || fragments.isEmpty() || fragments.size() > FragmentCollector.MAX_FRAGMENTS) return false;
        for (String fragment : fragments) {
            if (fragment == null || fragment.length() > 256) return false;
        }
        deliveries.add(new Delivery<>(sender, List.copyOf(targets), List.copyOf(fragments), now));
        return true;
    }

    /**
     * Returns the recorded null for the incremental relay delivery queue.
     *
     * @param now the now supplied to this operation
     * @return the result described above
     */
    Forward<T> poll(long now) {
        expire(now);
        Delivery<T> delivery = deliveries.peek();
        if (delivery == null) return null;
        Forward<T> next = new Forward<>(delivery.sender, delivery.targets.get(delivery.targetIndex),
                delivery.fragments.get(delivery.fragmentIndex));
        if (++delivery.targetIndex == delivery.targets.size()) {
            delivery.targetIndex = 0;
            if (++delivery.fragmentIndex == delivery.fragments.size()) removeFirst();
        }
        return next;
    }

    /**
     * Clears retained state in the incremental relay delivery queue.
     */
    void clear() {
        deliveries.clear();
    }

    /**
     * Performs the remove participant operation for the incremental relay delivery queue.
     *
     * @param participant the participant supplied to this operation
     */
    void removeParticipant(T participant) {
        var entries = deliveries.iterator();
        while (entries.hasNext()) {
            Delivery<T> delivery = entries.next();
            if (delivery.sender.equals(participant) || delivery.targets.contains(participant)) {
                entries.remove();
            }
        }
    }

    /**
     * Expires state whose deadline has elapsed in the incremental relay delivery queue.
     *
     * @param now the now supplied to this operation
     */
    private void expire(long now) {
        while (!deliveries.isEmpty() && now - deliveries.peek().createdAt >= LIFETIME_NANOS) removeFirst();
    }

    /**
     * Performs the remove first operation for the incremental relay delivery queue.
     */
    private void removeFirst() {
        deliveries.remove();
    }

    record Forward<T>(T sender, T target, String fragment) {}

    private static final class Delivery<T> {
        final T sender;
        final List<T> targets;
        final List<String> fragments;
        final long createdAt;
        int targetIndex;
        int fragmentIndex;

        /**
         * Creates a delivery with the supplied dependencies and initial state.
         *
         * @param sender the sender or source associated with this operation
         * @param targets the targets supplied to this operation
         * @param fragments the fragments supplied to this operation
         * @param createdAt the created at supplied to this operation
         */
        Delivery(T sender, List<T> targets, List<String> fragments, long createdAt) {
            this.sender = sender;
            this.targets = targets;
            this.fragments = fragments;
            this.createdAt = createdAt;
        }
    }
}
