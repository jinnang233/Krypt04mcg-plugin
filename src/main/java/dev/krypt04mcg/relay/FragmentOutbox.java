package dev.krypt04mcg.relay;

import java.util.ArrayDeque;
import java.util.List;

/** Main-thread-only delivery cursor. Bounds retained text and sends one fragment per poll. */
final class FragmentOutbox<T> {
    private static final int MAX_MESSAGES = 64;
    private static final int MAX_CHARS = 1024 * 1024;
    private static final long LIFETIME_NANOS = 10_000_000_000L;
    private final ArrayDeque<Delivery<T>> deliveries = new ArrayDeque<>();
    private int bufferedChars;

    boolean offer(T sender, List<T> targets, List<String> fragments, long now) {
        expire(now);
        if (deliveries.size() >= MAX_MESSAGES || targets.isEmpty() || targets.size() > 2
                || fragments.isEmpty() || fragments.size() > 1024) return false;
        int chars = 0;
        for (String fragment : fragments) {
            if (fragment == null || fragment.length() > 256) return false;
            chars += fragment.length();
        }
        if (chars > MAX_CHARS - bufferedChars) return false;
        deliveries.add(new Delivery<>(sender, List.copyOf(targets), List.copyOf(fragments), chars, now));
        bufferedChars += chars;
        return true;
    }

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

    void clear() {
        deliveries.clear();
        bufferedChars = 0;
    }

    void removeParticipant(T participant) {
        var entries = deliveries.iterator();
        while (entries.hasNext()) {
            Delivery<T> delivery = entries.next();
            if (delivery.sender.equals(participant) || delivery.targets.contains(participant)) {
                bufferedChars -= delivery.chars;
                entries.remove();
            }
        }
    }

    private void expire(long now) {
        while (!deliveries.isEmpty() && now - deliveries.peek().createdAt >= LIFETIME_NANOS) removeFirst();
    }

    private void removeFirst() {
        bufferedChars -= deliveries.remove().chars;
    }

    record Forward<T>(T sender, T target, String fragment) {}

    private static final class Delivery<T> {
        final T sender;
        final List<T> targets;
        final List<String> fragments;
        final int chars;
        final long createdAt;
        int targetIndex;
        int fragmentIndex;

        Delivery(T sender, List<T> targets, List<String> fragments, int chars, long createdAt) {
            this.sender = sender;
            this.targets = targets;
            this.fragments = fragments;
            this.chars = chars;
            this.createdAt = createdAt;
        }
    }
}
