package dev.krypt04mcg.relay.fragment;

import dev.krypt04mcg.relay.model.Fragment;
import dev.krypt04mcg.relay.util.Base64Url;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

public final class FragmentCollector {
    private final long timeoutNanos;
    private final LongSupplier clock;
    private final int maxMessages;
    private final int maxFragmentsPerMessage;
    private final Map<Key, PartialMessage> partials = new LinkedHashMap<>();
    private static final int MAX_BUFFERED_CHARS = 4 * 1024 * 1024;
    private int bufferedChars;

    public FragmentCollector(Duration timeout, int maxMessages, int maxFragmentsPerMessage) {
        this(timeout, maxMessages, maxFragmentsPerMessage, System::nanoTime);
    }

    FragmentCollector(Duration timeout, int maxMessages, int maxFragmentsPerMessage, LongSupplier clock) {
        if (timeout.isNegative() || timeout.isZero() || maxMessages < 1 || maxFragmentsPerMessage < 1) {
            throw new IllegalArgumentException("invalid collector limits");
        }
        try {
            this.timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("timeout is too large", e);
        }
        this.clock = clock;
        this.maxMessages = maxMessages;
        this.maxFragmentsPerMessage = maxFragmentsPerMessage;
    }

    public synchronized Optional<CompleteMessage> accept(UUID senderId, Fragment fragment, String rawLine) {
        cleanupTimedOut();
        if (fragment.total() <= 0 || fragment.total() > maxFragmentsPerMessage
                || fragment.index() < 0 || fragment.index() >= fragment.total()
                || fragment.payload() == null || fragment.payload().length() > 256
                || fragment.messageId() == null || fragment.messageId().length() > 32
                || rawLine == null || rawLine.length() > 256) {
            throw new IllegalArgumentException("invalid fragment or fragment limit exceeded");
        }

        Key key = new Key(senderId, fragment.messageId());
        if (partials.size() >= maxMessages && !partials.containsKey(key)) {
            evictOldest();
        }

        PartialMessage partial = partials.computeIfAbsent(key,
                ignored -> new PartialMessage(fragment.total(), clock.getAsLong()));
        if (partial.total != fragment.total()) {
            throw new IllegalArgumentException("fragment total changed");
        }

        if (!partial.payloads.containsKey(fragment.index())) {
            int added = fragment.payload().length() + rawLine.length();
            if (added > MAX_BUFFERED_CHARS - bufferedChars) {
                partials.remove(key);
                bufferedChars -= partial.chars;
                throw new IllegalArgumentException("fragment memory budget exhausted");
            }
            partial.payloads.put(fragment.index(), fragment.payload());
            partial.rawLines.put(fragment.index(), rawLine);
            partial.chars += added;
            bufferedChars += added;
        }

        if (!partial.complete()) {
            return Optional.empty();
        }

        StringBuilder encoded = new StringBuilder();
        String[] rawLines = new String[partial.total];
        for (int i = 0; i < partial.total; i++) {
            encoded.append(partial.payloads.get(i));
            rawLines[i] = partial.rawLines.get(i);
        }
        partials.remove(key);
        bufferedChars -= partial.chars;
        return Optional.of(new CompleteMessage(Base64Url.decode(encoded.toString()), List.of(rawLines)));
    }

    public synchronized int cleanupTimedOut() {
        long now = clock.getAsLong();
        int removed = 0;
        var entries = partials.values().iterator();
        while (entries.hasNext()) {
            PartialMessage partial = entries.next();
            if (now - partial.createdAt < timeoutNanos) break;
            bufferedChars -= partial.chars;
            entries.remove();
            removed++;
        }
        return removed;
    }

    public synchronized void removeSender(UUID senderId) {
        var entries = partials.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (entry.getKey().senderId().equals(senderId)) {
                bufferedChars -= entry.getValue().chars;
                entries.remove();
            }
        }
    }

    public synchronized void clear() {
        partials.clear();
        bufferedChars = 0;
    }

    private void evictOldest() {
        var entries = partials.values().iterator();
        if (entries.hasNext()) {
            bufferedChars -= entries.next().chars;
            entries.remove();
        }
    }

    private record Key(UUID senderId, String messageId) {
    }

    private static final class PartialMessage {
        private final int total;
        private final long createdAt;
        private final Map<Integer, String> payloads = new HashMap<>();
        private final Map<Integer, String> rawLines = new HashMap<>();
        private int chars;

        private PartialMessage(int total, long now) {
            this.total = total;
            this.createdAt = now;
        }

        private boolean complete() {
            return payloads.size() == total;
        }
    }

    public record CompleteMessage(byte[] packetBytes, List<String> fragmentsInOrder) {
    }
}
