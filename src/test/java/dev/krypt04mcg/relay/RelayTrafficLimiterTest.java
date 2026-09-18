package dev.krypt04mcg.relay;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RelayTrafficLimiterTest {
    @Test void tinyPacketsCannotBypassForwardingWorkBudget() {
        var limiter = new RelayTrafficLimiter();
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.receive(sender, 1, 0));
        for (int i = 0; i < 4096; i++) assertTrue(limiter.forward(sender, 1, 0));
        assertFalse(limiter.forward(sender, 1, 0));
        assertTrue(limiter.forward(sender, 1, 1000));
    }

    @Test void totalForwardingWorkIsBoundedAcrossSources() {
        var limiter = new RelayTrafficLimiter();
        for (int source = 0; source < 4; source++) {
            UUID sender = UUID.randomUUID();
            assertTrue(limiter.receive(sender, 1, 0));
            for (int i = 0; i < 4096; i++) assertTrue(limiter.forward(sender, 1, 0));
        }
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.receive(sender, 1, 0));
        assertFalse(limiter.forward(sender, 1, 0));
        assertTrue(limiter.forward(sender, 1, 1000));
    }

    @Test void broadcastScanningHasABudgetEvenWhenNobodySubscribes() {
        var limiter = new RelayTrafficLimiter();
        for (int source = 0; source < 4; source++) {
            UUID sender = UUID.randomUUID();
            assertTrue(limiter.receive(sender, 1, 0));
            for (int i = 0; i < 4096; i++) assertTrue(limiter.visitBroadcastRecipient(sender, 0));
            assertFalse(limiter.visitBroadcastRecipient(sender, 0));
        }
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.receive(sender, 1, 0));
        assertFalse(limiter.visitBroadcastRecipient(sender, 0));
        assertTrue(limiter.visitBroadcastRecipient(sender, 1000));
    }

    @Test void aggregateIngressIsBoundedAcrossManySenders() {
        var limiter = new RelayTrafficLimiter();
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            UUID sender = UUID.randomUUID();
            for (int j = 0; j < 512; j++) {
                if (limiter.receive(sender, 10, 0)) accepted++;
            }
        }
        assertEquals(4096, accepted);
        assertTrue(limiter.receive(UUID.randomUUID(), 10, 1000));
    }

    @Test void aggregateByteBudgetBoundsDecodeWorkAndClearReleasesSources() {
        var limiter = new RelayTrafficLimiter();
        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            UUID sender = UUID.randomUUID();
            for (int j = 0; j < 100; j++) {
                if (limiter.receive(sender, 100000, 0)) accepted++;
            }
        }
        assertEquals((16 * 1024 * 1024) / 100000, accepted);
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.receive(sender, 1, 1000));
        limiter.clear();
        assertFalse(limiter.forward(sender, 1, 1000));
    }

    @Test void throttlesFloodsAndRecoversWithoutAffectingOtherSources() {
        var limiter = new RelayTrafficLimiter();
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        for (int i = 0; i < 512; i++) assertTrue(limiter.receive(alice, 10, 0));
        assertFalse(limiter.receive(alice, 10, 0));
        assertTrue(limiter.receive(bob, 10, 0));
        assertTrue(limiter.receive(alice, 10, 1000));
    }

    @Test void chargesEachBroadcastRecipientAndBoundsAmplification() {
        var limiter = new RelayTrafficLimiter();
        UUID sender = UUID.randomUUID();
        assertTrue(limiter.receive(sender, 12000, 0));
        int forwarded = 0;
        while (limiter.forward(sender, 12000, 0)) forwarded++;
        assertTrue(forwarded > 0);
        assertTrue(forwarded * 12000L <= 32 * 1024 * 1024);
        assertFalse(limiter.forward(sender, 12000, 0));
        assertTrue(limiter.forward(sender, 12000, 1000));
    }

    @Test void allowsPacedTenMiBTransfer() {
        var limiter = new RelayTrafficLimiter();
        UUID sender = UUID.randomUUID();
        for (int chunk = 0; chunk < 2048; chunk++) {
            long now = (chunk / 4) * 50L;
            assertTrue(limiter.receive(sender, 12100, now));
            assertTrue(limiter.forward(sender, 12100, now));
        }
    }

    @Test void rejectsMalformedUtf8AndOverflowingVarints() {
        assertThrows(IllegalArgumentException.class, () -> CustomPayloadRelay.ServerboundPayload.decode(
                new byte[]{1, (byte) 0xFF, 1, 'x', 1}));
        assertThrows(IllegalArgumentException.class, () -> CustomPayloadRelay.ServerboundPayload.decode(
                new byte[]{1, 'a', 1, 'x', (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10}));
    }
}
