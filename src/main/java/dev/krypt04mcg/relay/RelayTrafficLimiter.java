package dev.krypt04mcg.relay;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Byte and packet budgets charged before decoding and for every forwarded recipient. */
final class RelayTrafficLimiter {
    enum ReceiveResult { ACCEPTED, SOURCE_LIMIT, GLOBAL_LIMIT }

    private final Map<UUID, Source> sources = new HashMap<>();
    private final Bucket egress = new Bucket(64 * 1024 * 1024, 32 * 1024 * 1024);
    private final Bucket ingressBytes = new Bucket(16 * 1024 * 1024, 8 * 1024 * 1024);
    private final Bucket ingressPackets = new Bucket(4096, 2048);
    private final Bucket egressPackets = new Bucket(16384, 8192);
    private final Bucket broadcastVisits = new Bucket(16384, 8192);

    /**
     * Performs the receive operation for the relay ingress and egress budgets.
     *
     * @param sender the sender or source associated with this operation
     * @param bytes the bytes supplied to this operation
     * @param now the now supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    boolean receive(UUID sender, int bytes, long now) {
        return receiveResult(sender, bytes, now) == ReceiveResult.ACCEPTED;
    }

    /**
     * Charges authenticated-source packet and byte buckets before aggregate ingress buckets and
     * distinguishes source overload from shared overload. Bounded source tracking expires idle entries
     * rather than growing without limit. The result lets the relay avoid disconnecting a compliant player
     * solely because a global budget is exhausted.
     *
     * @param sender the sender or source associated with this operation
     * @param bytes the bytes supplied to this operation
     * @param now the now supplied to this operation
     * @return the result described above
     */
    synchronized ReceiveResult receiveResult(UUID sender, int bytes, long now) {
        if (bytes < 0 || bytes > 100000) return ReceiveResult.SOURCE_LIMIT;
        Source source = sources.get(sender);
        if (source == null) {
            sources.values().removeIf(s -> now - s.lastSeen > 60000);
            if (sources.size() >= 1024) return ReceiveResult.GLOBAL_LIMIT;
            source = new Source(); sources.put(sender, source);
        }
        source.lastSeen = now;
        if (!source.packets.take(1, now) || !source.bytes.take(bytes, now)) return ReceiveResult.SOURCE_LIMIT;
        return ingressPackets.take(1, now) && ingressBytes.take(bytes, now)
                ? ReceiveResult.ACCEPTED : ReceiveResult.GLOBAL_LIMIT;
    }

    /**
     * Charges source and global outgoing packet/byte budgets for the intended relay send. Broadcast
     * recipients are charged separately, so one input cannot bypass aggregate cost by multiplying
     * recipients.
     *
     * @param sender the sender or source associated with this operation
     * @param bytes the bytes supplied to this operation
     * @param now the now supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    synchronized boolean forward(UUID sender, int bytes, long now) {
        if (bytes < 0) return false;
        Source source = sources.get(sender);
        return source != null && source.egressPackets.take(1, now) && egressPackets.take(1, now)
                && source.egress.take(bytes, now) && egress.take(bytes, now);
    }

    /**
     * Accounts for each broadcast candidate scan, including candidates that do not subscribe. This bounds
     * work spent enumerating recipients separately from actual network delivery.
     *
     * @param sender the sender or source associated with this operation
     * @param now the now supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    synchronized boolean visitBroadcastRecipient(UUID sender, long now) {
        Source source = sources.get(sender);
        return source != null && source.broadcastVisits.take(1, now) && broadcastVisits.take(1, now);
    }

    /**
     * Clears retained state in the relay ingress and egress budgets.
     */
    synchronized void clear() {
        sources.clear();
    }

    private static final class Source {
        long lastSeen;
        final Bucket bytes = new Bucket(4 * 1024 * 1024, 2 * 1024 * 1024);
        final Bucket packets = new Bucket(512, 256);
        final Bucket egress = new Bucket(32 * 1024 * 1024, 8 * 1024 * 1024);
        final Bucket egressPackets = new Bucket(4096, 2048);
        final Bucket broadcastVisits = new Bucket(4096, 2048);
    }

    private static final class Bucket {
        final int capacity, perSecond;
        double tokens;
        long updated = Long.MIN_VALUE;
        /**
         * Creates a bucket with the supplied dependencies and initial state.
         *
         * @param capacity the capacity supplied to this operation
         * @param perSecond the per second supplied to this operation
         */
        Bucket(int capacity, int perSecond) { this.capacity = capacity; this.perSecond = perSecond; tokens = capacity; }
        /**
         * Refills a token bucket from elapsed monotonic milliseconds up to its burst capacity, then debits the
         * requested cost only if enough tokens remain. Negative amounts fail, and backward time does not grant
         * a fresh refill.
         *
         * @param amount the amount supplied to this operation
         * @param now the now supplied to this operation
         * @return whether the condition or operation described above succeeds
         */
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
