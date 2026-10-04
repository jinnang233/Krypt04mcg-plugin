package dev.krypt04mcg.relay;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

import static dev.krypt04mcg.relay.CustomPayloadRelay.*;

/** Wire-compatible with the client's ControlPayload; bodies remain opaque. */
record StreamControl(Kind kind, String peer, UUID id, int slot, String channel,
                     String sessionId, long sequence, byte[] body) {
    enum Kind { EXCHANGE, OPEN, ASSIGNED, READY, END, RESET, ABORT }

    static StreamControl decode(byte[] bytes) {
        PayloadReader reader = new PayloadReader(bytes);
        int kind = reader.readVarInt();
        if (kind < 0 || kind >= Kind.values().length) throw new IllegalArgumentException("Invalid control kind");
        String peer = reader.readUtf(16);
        ByteBuffer fixed = ByteBuffer.wrap(reader.readBytes(20));
        UUID id = new UUID(fixed.getLong(), fixed.getLong());
        int slot = fixed.getInt();
        String channel = reader.readUtf(256), session = reader.readUtf(64);
        long sequence = ByteBuffer.wrap(reader.readBytes(8)).getLong();
        int length = reader.readVarInt();
        if (!peer.matches("[A-Za-z0-9_]{1,16}") || slot < -1 || slot >= 256
                || sequence < 0 || length < 0 || length > 30000) {
            throw new IllegalArgumentException("Invalid stream control");
        }
        byte[] body = reader.readBytes(length);
        if (!reader.finished()) throw new IllegalArgumentException("Trailing control data");
        return new StreamControl(Kind.values()[kind], peer, id, slot, channel, session, sequence, body);
    }

    byte[] encode(String source, Kind routedKind, int assignedSlot) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeVarInt(out, routedKind.ordinal());
        writeUtf(out, source, 16);
        out.writeBytes(ByteBuffer.allocate(20).putLong(id.getMostSignificantBits())
                .putLong(id.getLeastSignificantBits()).putInt(assignedSlot).array());
        writeUtf(out, channel, 256);
        writeUtf(out, sessionId, 64);
        out.writeBytes(ByteBuffer.allocate(8).putLong(sequence).array());
        writeVarInt(out, body.length);
        out.writeBytes(body);
        return out.toByteArray();
    }
}
