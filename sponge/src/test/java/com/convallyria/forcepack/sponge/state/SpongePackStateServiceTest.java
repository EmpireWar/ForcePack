package com.convallyria.forcepack.sponge.state;

import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import com.convallyria.forcepack.api.state.PackStateCodec;
import com.convallyria.forcepack.api.state.PackStateMessage;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpongePackStateServiceTest {

    private static final String SLOT = "battlegrounds:primary";
    private static final String SHA1 = "0e3736532caf3aa5bdc84b7f777bac690db5b481";

    private final UUID player = UUID.randomUUID();
    private final UUID session = UUID.randomUUID();
    private final List<PackStateMessage> sent = new ArrayList<>();
    private final List<PackStateSnapshot> delivered = new ArrayList<>();
    private final AtomicBoolean playerExists = new AtomicBoolean(true);
    private final AtomicBoolean connected = new AtomicBoolean(true);

    private final SpongePackStateService service = new SpongePackStateService(
            (id, payload) -> {
                if (!connected.get()) return false;
                sent.add(PackStateCodec.decode(payload));
                return true;
            },
            id -> playerExists.get(),
            (format, args) -> { });

    private SpongePackStateServiceTest() {
        service.subscribe(delivered::add);
    }

    private PackStateSnapshot snapshot(UUID forPlayer, UUID inSession, long generation, long sequence,
                                       ManagedPackStatus status) {
        return new PackStateSnapshot(forPlayer, inSession, generation, sequence, "v3-lobby",
                Collections.singletonList(new PackEntryState(SLOT, UUID.randomUUID(), SHA1, status)),
                status.isApplied() ? PackApplicationState.APPLIED : PackApplicationState.PENDING);
    }

    private void receive(PackStateMessage message) {
        service.handlePayload(player, PackStateCodec.encode(message));
    }

    @Test
    void anAcceptedSnapshotIsAcknowledgedAndDelivered() {
        service.markReady(player);
        receive(PackStateMessage.snapshot(snapshot(player, session, 1L, 4L,
                ManagedPackStatus.SUCCESSFULLY_LOADED)));

        assertEquals(1, delivered.size());
        assertTrue(delivered.get(0).isAppliedIn(SLOT));
        assertTrue(service.isAppliedIn(player, SLOT));
        assertFalse(service.isAppliedIn(player, "something:else"));

        assertEquals(1, sent.size());
        assertEquals(PackStateMessage.Kind.ACK, sent.get(0).kind());
        assertEquals(4L, sent.get(0).ackSequence(), "the acknowledgement names the sequence applied");
    }

    @Test
    void stateFromTheConfigurationPhaseIsHeldUntilThePlayerExists() {
        // No player object yet: a listener could not act on this.
        receive(PackStateMessage.snapshot(snapshot(player, session, 1L, 1L,
                ManagedPackStatus.SUCCESSFULLY_LOADED)));

        assertTrue(delivered.isEmpty(), "nothing is delivered before the backend player exists");
        assertTrue(service.current(player).isPresent(), "but it is still the current state");
        assertEquals(1, sent.size(), "and it is still acknowledged");

        service.markReady(player);

        assertEquals(1, delivered.size(), "the buffered state is released exactly once");
        service.markReady(player);
        assertEquals(1, delivered.size(), "and not again");
    }

    @Test
    void overtakenMessagesChangeNothing() {
        service.markReady(player);
        receive(PackStateMessage.snapshot(snapshot(player, session, 2L, 5L,
                ManagedPackStatus.SUCCESSFULLY_LOADED)));
        sent.clear();
        delivered.clear();

        receive(PackStateMessage.snapshot(snapshot(player, session, 2L, 5L, ManagedPackStatus.DECLINED)));
        receive(PackStateMessage.snapshot(snapshot(player, session, 2L, 4L, ManagedPackStatus.DECLINED)));
        receive(PackStateMessage.snapshot(snapshot(player, session, 1L, 99L, ManagedPackStatus.DECLINED)));
        // A different connection entirely. State never crosses sessions.
        receive(PackStateMessage.snapshot(snapshot(player, UUID.randomUUID(), 9L, 9L,
                ManagedPackStatus.DECLINED)));

        assertTrue(delivered.isEmpty(), "a stale message must not reach a listener");
        assertTrue(sent.isEmpty(), "nor be acknowledged");
        assertTrue(service.isAppliedIn(player, SLOT), "nor undo the state that superseded it");

        receive(PackStateMessage.snapshot(snapshot(player, session, 3L, 0L, ManagedPackStatus.REMOVED)));
        assertEquals(1, delivered.size(), "a later generation is applied");
        assertFalse(service.isAppliedIn(player, SLOT), "removal stops readiness");
    }

    @Test
    void aSnapshotAboutAnotherPlayerIsDiscarded() {
        service.markReady(player);
        // The connection identifies the player; the payload does not get to claim otherwise.
        service.handlePayload(player, PackStateCodec.encode(PackStateMessage.snapshot(
                snapshot(UUID.randomUUID(), session, 1L, 1L, ManagedPackStatus.SUCCESSFULLY_LOADED))));

        assertTrue(delivered.isEmpty());
        assertFalse(service.current(player).isPresent());
    }

    @Test
    void unreadableAndWrongWayRoundMessagesAreIgnored() {
        service.markReady(player);

        service.handlePayload(player, new byte[] {1, 2, 3});
        service.handlePayload(player, new byte[0]);
        receive(PackStateMessage.request());
        receive(PackStateMessage.ack(7L));

        assertTrue(delivered.isEmpty());
        assertTrue(sent.isEmpty());
        assertFalse(service.current(player).isPresent());
    }

    @Test
    void requestingStateSendsARequest() {
        service.requestSnapshot(player);

        assertEquals(1, sent.size());
        assertEquals(PackStateMessage.Kind.REQUEST, sent.get(0).kind());

        connected.set(false);
        service.requestSnapshot(player);
        assertEquals(1, sent.size(), "with no connection there is nothing to ask on");
    }

    @Test
    void stateDoesNotSurviveTheConnection() {
        service.markReady(player);
        receive(PackStateMessage.snapshot(snapshot(player, session, 1L, 1L,
                ManagedPackStatus.SUCCESSFULLY_LOADED)));
        assertTrue(service.current(player).isPresent());

        service.forget(player);

        assertFalse(service.current(player).isPresent());
        assertFalse(service.isAppliedIn(player, SLOT), "a new connection starts from unknown");

        // A reconnect starts buffering again rather than inheriting the old readiness.
        playerExists.set(false);
        receive(PackStateMessage.snapshot(snapshot(player, UUID.randomUUID(), 1L, 1L,
                ManagedPackStatus.SUCCESSFULLY_LOADED)));
        assertEquals(1, delivered.size(), "still only the delivery from before the disconnect");
    }
}
