package dev.krypt04mcg.relay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Byte and packet budgets charged before decoding and for every forwarded recipient. */
final class RelayTrafficLimiter {
    private final Map<UUID, Source> sources = new HashMap<>();
    private final Bucket egress = new Bucket(64 * 1024 * 1024, 32 * 1024 * 1024);
    private final Bucket ingressBytes = new Bucket(16 * 1024 * 1024, 8 * 1024 * 1024);
    private final Bucket ingressPackets = new Bucket(4096, 2048);

    synchronized boolean receive(UUID sender, int bytes, long now) {
        if (bytes < 0 || bytes > 100000) return false;
        Source source = sources.get(sender);
        if (source == null) {
            sources.values().removeIf(s -> now - s.lastSeen > 60000);
            if (sources.size() >= 1024) return false;
            source = new Source(); sources.put(sender, source);
        }
        source.lastSeen = now;
        return source.packets.take(1, now) && source.bytes.take(bytes, now)
                && ingressPackets.take(1, now) && ingressBytes.take(bytes, now);
    }

    synchronized boolean forward(UUID sender, int bytes, long now) {
        Source source = sources.get(sender);
        return source != null && source.egress.take(bytes, now) && egress.take(bytes, now);
    }

    synchronized void clear() {
        sources.clear();
    }

    private static final class Source {
        long lastSeen;
        final Bucket bytes = new Bucket(4 * 1024 * 1024, 2 * 1024 * 1024);
        final Bucket packets = new Bucket(512, 256);
        final Bucket egress = new Bucket(32 * 1024 * 1024, 8 * 1024 * 1024);
    }

    private static final class Bucket {
        final int capacity, perSecond;
        double tokens;
        long updated = Long.MIN_VALUE;
        Bucket(int capacity, int perSecond) { this.capacity = capacity; this.perSecond = perSecond; tokens = capacity; }
        boolean take(int amount, long now) {
            if (amount < 0) return false;
            if (updated != Long.MIN_VALUE && now > updated)
                tokens = Math.min(capacity, tokens + (now - updated) * (double) perSecond / 1000);
            updated = Math.max(updated, now);
            if (tokens < amount) return false;
            tokens -= amount; return true;
        }
    }
}
