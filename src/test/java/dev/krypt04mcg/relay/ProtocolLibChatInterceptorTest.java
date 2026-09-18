package dev.krypt04mcg.relay;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolLibChatInterceptorTest {
    @Test void extractsSupportedPrivateCommandsOnly() {
        assertEquals("[KRYPT04MCG] body", ProtocolLibChatInterceptor.extractPrivateMessageBody(
                "/minecraft:tell Bob [KRYPT04MCG] body"));
        assertEquals("hello", ProtocolLibChatInterceptor.extractPrivateMessageBody("msg Bob hello"));
        assertNull(ProtocolLibChatInterceptor.extractPrivateMessageBody("say hello"));
        assertNull(ProtocolLibChatInterceptor.extractPrivateMessageBody(null));
    }

    @Test @Timeout(2) void rejectsOversizedWhitespaceBeforeRegexBacktracking() {
        assertNull(ProtocolLibChatInterceptor.extractPrivateMessageBody(
                "tell Bob " + " ".repeat(100_000) + "x\n"));
    }
}
