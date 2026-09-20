package com.convallyria.forcepack.api.state;

import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackStateCodecTest {

    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID PACK = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final String SHA1 = "0e3736532caf3aa5bdc84b7f777bac690db5b481";

    private static PackStateSnapshot snapshot(long generation, long sequence, List<PackEntryState> entries) {
        return new PackStateSnapshot(PLAYER, SESSION, generation, sequence, "v3-lobby", entries,
                entries.isEmpty() ? PackApplicationState.NONE : PackApplicationState.APPLIED);
    }

    @Test
    void snapshotSurvivesRoundTrip() {
        final List<PackEntryState> entries = Arrays.asList(
                new PackEntryState("battlegrounds:primary", PACK, SHA1, ManagedPackStatus.SUCCESSFULLY_LOADED),
                new PackEntryState("other:overlay", UUID.randomUUID(), SHA1, ManagedPackStatus.REMOVED));
        final PackStateSnapshot original = snapshot(3L, 7L, entries);

        final PackStateMessage decoded = PackStateCodec.decode(
                PackStateCodec.encode(PackStateMessage.snapshot(original)));

        assertEquals(PackStateMessage.Kind.SNAPSHOT, decoded.kind());
        final PackStateSnapshot result = decoded.snapshot().orElseThrow(AssertionError::new);
        assertEquals(PLAYER, result.player());
        assertEquals(SESSION, result.sessionId());
        assertEquals(3L, result.generation());
        assertEquals(7L, result.sequence());
        assertEquals("v3-lobby", result.serverName().orElseThrow(AssertionError::new));
        assertEquals(entries, result.entries());
        assertEquals(PackApplicationState.APPLIED, result.aggregate());
        assertTrue(result.isAppliedIn("battlegrounds:primary"));
        // A removed pack in another slot is not readiness for this one.
        assertFalse(result.isAppliedIn("other:overlay"));
    }

    @Test
    void absentServerNameRoundTripsAsAbsent() {
        final PackStateSnapshot original = new PackStateSnapshot(PLAYER, SESSION, 0L, 0L, null,
                Collections.emptyList(), PackApplicationState.UNKNOWN);

        final PackStateSnapshot result = PackStateCodec
                .decode(PackStateCodec.encode(PackStateMessage.snapshot(original)))
                .snapshot().orElseThrow(AssertionError::new);

        assertFalse(result.serverName().isPresent());
        assertTrue(result.entries().isEmpty());
    }

    @Test
    void requestAndAckRoundTrip() {
        assertEquals(PackStateMessage.Kind.REQUEST,
                PackStateCodec.decode(PackStateCodec.encode(PackStateMessage.request())).kind());

        final PackStateMessage ack = PackStateCodec.decode(PackStateCodec.encode(PackStateMessage.ack(42L)));
        assertEquals(PackStateMessage.Kind.ACK, ack.kind());
        assertEquals(42L, ack.ackSequence());
    }

    @Test
    void encodingIsDeterministic() {
        final PackStateMessage message = PackStateMessage.snapshot(snapshot(1L, 1L,
                Collections.singletonList(new PackEntryState("k", PACK, SHA1, ManagedPackStatus.SENT))));
        assertArrayEquals(PackStateCodec.encode(message), PackStateCodec.encode(message));
    }

    @Test
    void foreignSchemaVersionIsRejected() {
        final byte[] payload = PackStateCodec.encode(PackStateMessage.request());
        payload[3] = (byte) (payload[3] + 1);
        final IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> PackStateCodec.decode(payload));
        assertTrue(error.getMessage().contains("unsupported schema version"));
    }

    @Test
    void truncatedPayloadIsRejected() {
        final byte[] payload = PackStateCodec.encode(PackStateMessage.snapshot(snapshot(1L, 1L,
                Collections.singletonList(new PackEntryState("k", PACK, SHA1, ManagedPackStatus.SENT)))));
        final byte[] truncated = Arrays.copyOf(payload, payload.length - 5);
        assertThrows(IllegalArgumentException.class, () -> PackStateCodec.decode(truncated));
    }

    @Test
    void unknownStatusNameIsRejectedRatherThanGuessed() {
        // An older backend must not silently read a newer status as something else.
        final byte[] payload = PackStateCodec.encode(PackStateMessage.snapshot(snapshot(1L, 1L,
                Collections.singletonList(new PackEntryState("k", PACK, SHA1, ManagedPackStatus.SENT)))));
        final String encoded = new String(payload, java.nio.charset.StandardCharsets.ISO_8859_1);
        final int at = encoded.indexOf("SENT");
        assertTrue(at > 0, "test fixture no longer contains the status name");
        payload[at] = 'X';
        assertThrows(IllegalArgumentException.class, () -> PackStateCodec.decode(payload));
    }

    @Test
    void oversizedEntryListIsRejected() {
        final PackEntryState entry = new PackEntryState("k", PACK, SHA1, ManagedPackStatus.SENT);
        final PackEntryState[] tooMany = new PackEntryState[PackStateSnapshot.MAX_ENTRIES + 1];
        Arrays.fill(tooMany, entry);
        assertThrows(IllegalArgumentException.class, () -> snapshot(1L, 1L, Arrays.asList(tooMany)));
    }

    @Test
    void staleSnapshotsDoNotSupersede() {
        final PackStateSnapshot current = snapshot(2L, 5L, Collections.emptyList());

        assertTrue(snapshot(2L, 6L, Collections.emptyList()).supersedes(current), "later sequence");
        assertTrue(snapshot(3L, 0L, Collections.emptyList()).supersedes(current), "later generation");
        assertFalse(snapshot(2L, 5L, Collections.emptyList()).supersedes(current), "same sequence");
        assertFalse(snapshot(2L, 4L, Collections.emptyList()).supersedes(current), "earlier sequence");
        assertFalse(snapshot(1L, 99L, Collections.emptyList()).supersedes(current), "earlier generation");
        assertTrue(current.supersedes(null), "nothing applied yet");

        final PackStateSnapshot otherSession = new PackStateSnapshot(PLAYER, UUID.randomUUID(), 9L, 9L,
                "v3-lobby", Collections.emptyList(), PackApplicationState.NONE);
        assertFalse(otherSession.supersedes(current), "state must never cross sessions");
    }
}
