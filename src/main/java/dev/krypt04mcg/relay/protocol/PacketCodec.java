package dev.krypt04mcg.relay.protocol;

import dev.krypt04mcg.relay.model.AlgorithmSuite;
import dev.krypt04mcg.relay.model.EncryptedPacket;
import dev.krypt04mcg.relay.model.PacketType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;

public final class PacketCodec {
    private static final byte FLAG_SIGNED = 0x01;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_BYTES32_FIELD_BYTES = 1024 * 1024;

    /**
     * Parses a bounded versioned packet, validates lengths and field combinations, and rejects truncation
     * or trailing bytes. Decoded sender/routing fields are claims until the transport and cryptographic
     * receive paths validate them; parsing is not signature verification.
     *
     * @param encoded the encoded bytes to parse or verify
     * @return the result described above
     */
    public EncryptedPacket decode(byte[] encoded) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            byte version = in.readByte();
            if (version != EncryptedPacket.LEGACY_VERSION
                    && version != EncryptedPacket.PREVIOUS_VERSION
                    && version != EncryptedPacket.COMPACT_VERSION
                    && version != EncryptedPacket.VERSION) {
                throw new IOException("unsupported protocol version: " + Byte.toUnsignedInt(version));
            }
            PacketType type = PacketType.fromId(in.readUnsignedByte());
            byte flags = in.readByte();
            if (isSessionV4(version, type) && isSigned(flags)) {
                throw new IOException("session messages cannot be signed");
            }
            String sender = readString(in);
            String receiver = readString(in);
            long timestamp = in.readLong();
            byte[] messageId = in.readNBytes(16);
            if (messageId.length != 16) {
                throw new IOException("truncated message id");
            }
            String sessionId = isSessionV4(version, type) ? readString(in) : "";
            long sequence = isSessionV4(version, type) ? in.readLong() : 0;
            short aadFragmentIndex = 0;
            short aadFragmentTotal = 1;
            if (version < EncryptedPacket.COMPACT_VERSION) {
                aadFragmentIndex = in.readShort();
                aadFragmentTotal = in.readShort();
            }
            String kem = version < EncryptedPacket.COMPACT_VERSION || usesKem(type) ? readString(in) : "NONE";
            String signatureAlgorithm = version < EncryptedPacket.COMPACT_VERSION || isSigned(flags)
                    ? readString(in)
                    : "NONE";
            AlgorithmSuite algorithms = new AlgorithmSuite(kem, signatureAlgorithm, readString(in), readString(in));
            byte[] nonce = readBytes16(in);
            byte[] kemCiphertext = readBytes32(in);
            byte[] ciphertext = readBytes32(in);
            byte[] signature = isSessionV4(version, type) ? new byte[0] : readBytes32(in);
            if (in.available() != 0) {
                throw new IOException("trailing packet bytes: " + in.available());
            }
            return new EncryptedPacket(version, type, flags, sender, receiver, timestamp, messageId,
                    aadFragmentIndex, aadFragmentTotal, algorithms, nonce, kemCiphertext, ciphertext, signature,
                    sessionId, sequence);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid Krypt04Mcg packet", e);
        }
    }

    /**
     * Reads a bounded length-prefixed UTF-8 field. The client codec uses strict malformed-input reporting
     * because these strings are reconstructed into AAD and signature input; callers must not assume
     * successful parsing establishes authentication.
     *
     * @param in the in supplied to this operation
     * @return the result described above
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_BYTES) {
            throw new IOException("string field too long: " + length);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(readExact(in, length, "string"))).toString();
    }

    /**
     * Reads bytes16 from the input used by the versioned encrypted-packet codec.
     *
     * @param in the in supplied to this operation
     * @return the resulting array produced by this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static byte[] readBytes16(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        return readExact(in, length, "bytes16");
    }

    /**
     * Reads bytes32 from the input used by the versioned encrypted-packet codec.
     *
     * @param in the in supplied to this operation
     * @return the resulting array produced by this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static byte[] readBytes32(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) {
            throw new IOException("negative length");
        }
        if (length > MAX_BYTES32_FIELD_BYTES) {
            throw new IOException("field too long: " + length);
        }
        return readExact(in, length, "bytes32");
    }

    /**
     * Reads the declared bounded field length and rejects a short read instead of accepting a truncated
     * authenticated representation.
     *
     * @param in the in supplied to this operation
     * @param length the requested or declared byte count
     * @param field the field supplied to this operation
     * @return the resulting array produced by this operation
     * @throws IOException if input/output, stored-state validation or resource handling fails
     */
    private static byte[] readExact(DataInputStream in, int length, String field) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated " + field);
        }
        return bytes;
    }

    /**
     * Reports whether session v4 holds for the versioned encrypted-packet codec.
     *
     * @param version the version supplied to this operation
     * @param type the type supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private static boolean isSessionV4(byte version, PacketType type) {
        return version >= EncryptedPacket.VERSION && type == PacketType.SESSION_MESSAGE;
    }

    /**
     * Performs the uses kem operation for the versioned encrypted-packet codec.
     *
     * @param type the type supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private static boolean usesKem(PacketType type) {
        return type != PacketType.SESSION_MESSAGE;
    }

    /**
     * Reports whether signed holds for the versioned encrypted-packet codec.
     *
     * @param flags the flags supplied to this operation
     * @return whether the condition or operation described above succeeds
     */
    private static boolean isSigned(byte flags) {
        return (flags & FLAG_SIGNED) != 0;
    }
}
