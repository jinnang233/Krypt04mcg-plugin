package dev.krypt04mcg.relay.protocol;

import dev.krypt04mcg.relay.model.AlgorithmSuite;
import dev.krypt04mcg.relay.model.EncryptedPacket;
import dev.krypt04mcg.relay.model.PacketType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class PacketCodec {
    private static final byte FLAG_SIGNED = 0x01;
    private static final int MAX_STRING_BYTES = 4096;
    private static final int MAX_BYTES32_FIELD_BYTES = 1024 * 1024;

    public EncryptedPacket decode(byte[] encoded) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded));
            byte version = in.readByte();
            if (version != EncryptedPacket.LEGACY_VERSION
                    && version != EncryptedPacket.PREVIOUS_VERSION
                    && version != EncryptedPacket.VERSION) {
                throw new IOException("unsupported protocol version: " + Byte.toUnsignedInt(version));
            }
            PacketType type = PacketType.fromId(in.readUnsignedByte());
            byte flags = in.readByte();
            String sender = readString(in);
            String receiver = readString(in);
            long timestamp = in.readLong();
            byte[] messageId = in.readNBytes(16);
            if (messageId.length != 16) {
                throw new IOException("truncated message id");
            }
            short aadFragmentIndex = 0;
            short aadFragmentTotal = 1;
            if (version < EncryptedPacket.VERSION) {
                aadFragmentIndex = in.readShort();
                aadFragmentTotal = in.readShort();
            }
            String kem = version < EncryptedPacket.VERSION || usesKem(type) ? readString(in) : "NONE";
            String signatureAlgorithm = version < EncryptedPacket.VERSION || isSigned(flags)
                    ? readString(in)
                    : "NONE";
            AlgorithmSuite algorithms = new AlgorithmSuite(kem, signatureAlgorithm, readString(in), readString(in));
            byte[] nonce = readBytes16(in);
            byte[] kemCiphertext = readBytes32(in);
            byte[] ciphertext = readBytes32(in);
            byte[] signature = readBytes32(in);
            if (in.available() != 0) {
                throw new IOException("trailing packet bytes: " + in.available());
            }
            return new EncryptedPacket(version, type, flags, sender, receiver, timestamp, messageId,
                    aadFragmentIndex, aadFragmentTotal, algorithms, nonce, kemCiphertext, ciphertext, signature);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid Krypt04Mcg packet", e);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_BYTES) {
            throw new IOException("string field too long: " + length);
        }
        return new String(readExact(in, length, "string"), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes16(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        return readExact(in, length, "bytes16");
    }

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

    private static byte[] readExact(DataInputStream in, int length, String field) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("truncated " + field);
        }
        return bytes;
    }

    private static boolean usesKem(PacketType type) {
        return type != PacketType.SESSION_MESSAGE;
    }

    private static boolean isSigned(byte flags) {
        return (flags & FLAG_SIGNED) != 0;
    }
}
