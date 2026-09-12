package dev.krypt04mcg.relay.protocol;

import dev.krypt04mcg.relay.fragment.FragmentCollector;
import dev.krypt04mcg.relay.fragment.FragmentService;
import dev.krypt04mcg.relay.model.PacketType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

final class PacketCodecTest {
    private static final String MESSAGE_ID = "00112233445566778899aabbccddeeff";
    private final PacketCodec codec = new PacketCodec();

    // Frozen wire bytes emitted by the current Krypt04Mcg client encoder, not the relay.
    static Stream<String> clientPackets() throws Exception {
        try (var reader = new BufferedReader(new InputStreamReader(
                PacketCodecTest.class.getResourceAsStream("/client-packets.txt"), StandardCharsets.UTF_8))) {
            return reader.lines().filter(line -> !line.startsWith("#")).toList().stream();
        }
    }

    @ParameterizedTest
    @MethodSource("clientPackets")
    void decodesClientWireBytes(String fixture) {
        String[] fields = fixture.split(" ");
        int version = Integer.parseInt(fields[0]);
        PacketType type = PacketType.valueOf(fields[1]);
        int flags = Integer.parseInt(fields[2]);
        var packet = codec.decode(HexFormat.of().parseHex(fields[3]));
        boolean sessionV4 = version == 4 && type == PacketType.SESSION_MESSAGE;
        assertEquals(version, packet.protocolVersion());
        assertEquals(type, packet.type());
        assertEquals(flags, packet.flags());
        assertEquals("Alice", packet.sender());
        assertEquals("Bob", packet.receiver());
        assertEquals(123456789L, packet.timestampMillis());
        assertArrayEquals(HexFormat.of().parseHex(MESSAGE_ID), packet.messageId());
        assertEquals(version < 3 ? 2 : 0, packet.aadFragmentIndex());
        assertEquals(version < 3 ? 9 : 1, packet.aadFragmentTotal());
        assertEquals(type == PacketType.SESSION_MESSAGE ? "NONE" : "ML-KEM-768", packet.algorithms().kem());
        assertEquals(flags == 1 ? "ML-DSA-65" : "NONE", packet.algorithms().signature());
        assertEquals("AES-256-GCM", packet.algorithms().aead());
        assertEquals("HKDF-SHA256", packet.algorithms().hkdf());
        assertArrayEquals(new byte[]{1, 2, 3}, packet.nonce());
        assertArrayEquals(type == PacketType.SESSION_MESSAGE ? new byte[0] : new byte[]{4, 5}, packet.kemCiphertext());
        assertArrayEquals(new byte[]{6, 7, 8, 9}, packet.ciphertext());
        assertArrayEquals(flags == 1 ? new byte[]{10, 11} : new byte[0], packet.signature());
        assertEquals(sessionV4 ? "AAAAAAAAAAAAAAAAAAAAAA" : "", packet.sessionId());
        assertEquals(sessionV4 ? 4294967297L : 0, packet.sequence());
    }

    @ParameterizedTest
    @MethodSource("clientPackets")
    void rejectsEveryTruncationAndTrailingBytes(String fixture) {
        byte[] bytes = wireBytes(fixture);
        for (int length = 0; length < bytes.length; length++) {
            byte[] truncated = Arrays.copyOf(bytes, length);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(truncated), "length=" + length);
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(Arrays.copyOf(bytes, bytes.length + 4)));
    }

    @Test
    void rejectsSignedV4SessionUnknownVersionAndMalformedUtf8() throws Exception {
        byte[] bytes = sessionPacket();
        bytes[2] = 1;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
        bytes[2] = 0;
        bytes[0] = 5;
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
        bytes[0] = 4;
        bytes[5] = (byte) 0xff; // First UTF-8 byte of sender, after its u16 length.
        assertThrows(IllegalArgumentException.class, () -> codec.decode(bytes));
    }

    @Test
    void reassemblesV4SessionFragmentsWithoutChangingForwardedLines() throws Exception {
        byte[] bytes = sessionPacket();
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        List<String> lines = List.of(
                "[KRYPT04MCG] " + MESSAGE_ID + " 0 2 " + encoded.substring(0, 80),
                "[KRYPT04MCG] " + MESSAGE_ID + " 1 2 " + encoded.substring(80));
        var parser = new FragmentService();
        var collector = new FragmentCollector(Duration.ofMinutes(2), 128, 256);
        UUID sender = UUID.randomUUID();
        assertTrue(collector.accept(sender, parser.parse(lines.get(1)), lines.get(1)).isEmpty());
        var complete = collector.accept(sender, parser.parse(lines.get(0)), lines.get(0)).orElseThrow();
        assertArrayEquals(bytes, complete.packetBytes());
        assertEquals(lines, complete.fragmentsInOrder());
        assertEquals("Bob", codec.decode(complete.packetBytes()).receiver());
        assertEquals(4294967297L, codec.decode(complete.packetBytes()).sequence());
    }

    private static byte[] sessionPacket() throws Exception {
        return clientPackets().filter(line -> line.startsWith("4 SESSION_MESSAGE 0 "))
                .map(PacketCodecTest::wireBytes).findFirst().orElseThrow();
    }

    private static byte[] wireBytes(String fixture) {
        return HexFormat.of().parseHex(fixture.split(" ")[3]);
    }
}
