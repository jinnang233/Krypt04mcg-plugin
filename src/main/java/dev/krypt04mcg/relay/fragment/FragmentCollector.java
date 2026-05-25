package dev.krypt04mcg.relay.fragment;

import dev.krypt04mcg.relay.model.Fragment;
import dev.krypt04mcg.relay.util.Base64Url;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FragmentCollector {
    private final Duration timeout;
    private final int maxMessages;
    private final int maxFragmentsPerMessage;
    private final Map<Key, PartialMessage> partials = new HashMap<>();

    public FragmentCollector(Duration timeout, int maxMessages, int maxFragmentsPerMessage) {
        this.timeout = timeout;
        this.maxMessages = maxMessages;
        this.maxFragmentsPerMessage = maxFragmentsPerMessage;
    }

    public synchronized Optional<CompleteMessage> accept(UUID senderId, Fragment fragment, String rawLine) {
        cleanupTimedOut();
        if (fragment.total() > maxFragmentsPerMessage) {
            throw new IllegalArgumentException("too many fragments: " + fragment.total());
        }

        Key key = new Key(senderId, fragment.messageId());
        if (partials.size() >= maxMessages && !partials.containsKey(key)) {
            evictOldest();
        }

        PartialMessage partial = partials.computeIfAbsent(key,
                ignored -> new PartialMessage(fragment.total(), System.currentTimeMillis()));
        if (partial.total != fragment.total()) {
            throw new IllegalArgumentException("fragment total changed");
        }

        partial.payloads.putIfAbsent(fragment.index(), fragment.payload());
        partial.rawLines.putIfAbsent(fragment.index(), rawLine);
        partial.lastTouched = System.currentTimeMillis();

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
        return Optional.of(new CompleteMessage(Base64Url.decode(encoded.toString()), List.of(rawLines)));
    }

    public synchronized int cleanupTimedOut() {
        long cutoff = System.currentTimeMillis() - timeout.toMillis();
        List<Key> expired = partials.entrySet().stream()
                .filter(entry -> entry.getValue().lastTouched < cutoff)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(partials::remove);
        return expired.size();
    }

    public synchronized void clear() {
        partials.clear();
    }

    private void evictOldest() {
        Key oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<Key, PartialMessage> entry : partials.entrySet()) {
            if (entry.getValue().lastTouched < oldestTime) {
                oldestTime = entry.getValue().lastTouched;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            partials.remove(oldestKey);
        }
    }

    private record Key(UUID senderId, String messageId) {
    }

    private static final class PartialMessage {
        private final int total;
        private final long createdAt;
        private final Map<Integer, String> payloads = new HashMap<>();
        private final Map<Integer, String> rawLines = new HashMap<>();
        private long lastTouched;

        private PartialMessage(int total, long now) {
            this.total = total;
            this.createdAt = now;
            this.lastTouched = now;
        }

        private boolean complete() {
            return payloads.size() == total;
        }
    }

    public record CompleteMessage(byte[] packetBytes, List<String> fragmentsInOrder) {
    }
}
