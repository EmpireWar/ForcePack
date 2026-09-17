package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.ApplyResult;
import com.convallyria.forcepack.api.managed.FailurePolicy;
import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import com.convallyria.forcepack.api.managed.PlayerPackState;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackStateTrackerTest {

    private static final String SLOT = "battlegrounds:primary";
    private static final String OTHER_SLOT = "other:overlay";
    private static final String SHA_A = "0e3736532caf3aa5bdc84b7f777bac690db5b481";
    private static final String SHA_B = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private final UUID player = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final PackStateTracker tracker = new PackStateTracker(clock::get);

    private static PackEntryState pending(String slot, UUID id, String sha1) {
        return new PackEntryState(slot, id, sha1, ManagedPackStatus.PENDING);
    }

    private CompletableFuture<ApplyResult> select(long generation, List<PackEntryState> desired) {
        final CompletableFuture<ApplyResult> completion = new CompletableFuture<>();
        tracker.setDesired(player, generation, desired, true, FailurePolicy.KICK, 120_000L, completion);
        return completion;
    }

    @Test
    void replacementCallbackSeesCommittedNewSelection() {
        final UUID nextOffer = UUID.randomUUID();
        final CompletableFuture<ApplyResult> old = select(tracker.beginGeneration(player, "siege"),
                Collections.singletonList(pending(SLOT, UUID.randomUUID(), SHA_A)));
        final CompletableFuture<Boolean> observed = old.thenApply(result ->
                result.terminalStatus() == ManagedPackStatus.CANCELLED
                        && tracker.snapshot(player).desired().get(0).packId().equals(nextOffer));
        final CompletableFuture<ApplyResult> next = select(tracker.beginGeneration(player, "siege"),
                Collections.singletonList(pending(SLOT, nextOffer, SHA_B)));
        assertTrue(observed.join(), "callbacks must run after the entire replacement mutation");
        assertTrue(tracker.onStatus(player, nextOffer, ManagedPackStatus.SUCCESSFULLY_LOADED));
        assertTrue(next.join().success());
    }

    @Test
    void progressStatusesDoNotCompleteTheOperation() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> completion =
                select(generation, Collections.singletonList(pending(SLOT, offer, SHA_A)));

        tracker.markSent(player, offer);
        tracker.onStatus(player, offer, ManagedPackStatus.ACCEPTED);
        tracker.onStatus(player, offer, ManagedPackStatus.DOWNLOADED);

        assertFalse(completion.isDone(), "accepted and downloaded are progress, not success");
        assertEquals(PackApplicationState.PENDING, tracker.snapshot(player).aggregate());
        assertFalse(tracker.snapshot(player).isAppliedIn(SLOT));

        tracker.onStatus(player, offer, ManagedPackStatus.SUCCESSFULLY_LOADED);

        assertTrue(completion.isDone());
        final ApplyResult result = completion.join();
        assertTrue(result.success());
        assertEquals(PackApplicationState.APPLIED, result.state().aggregate());
        assertTrue(result.state().isAppliedIn(SLOT));
    }

    @Test
    void aDeclineFailsTheOperationAndLeavesNothingApplied() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> completion =
                select(generation, Collections.singletonList(pending(SLOT, offer, SHA_A)));

        tracker.onStatus(player, offer, ManagedPackStatus.DECLINED);

        final ApplyResult result = completion.join();
        assertFalse(result.success());
        assertEquals(ManagedPackStatus.DECLINED, result.terminalStatus());
        assertFalse(result.recovered());
        assertEquals(PackApplicationState.FAILED, result.state().aggregate());
        assertTrue(result.state().applied().isEmpty());
    }

    @Test
    void aPartlyAppliedSelectionIsNotSuccess() {
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> completion = select(generation,
                Arrays.asList(pending(SLOT, first, SHA_A), pending(OTHER_SLOT, second, SHA_B)));

        tracker.onStatus(player, first, ManagedPackStatus.SUCCESSFULLY_LOADED);
        assertFalse(completion.isDone(), "the operation is not over while one pack is outstanding");
        tracker.onStatus(player, second, ManagedPackStatus.FAILED_DOWNLOAD);

        final ApplyResult result = completion.join();
        assertFalse(result.success());
        assertEquals(PackApplicationState.PARTIAL, result.state().aggregate());
        assertTrue(result.state().isAppliedIn(SLOT));
        assertFalse(result.state().isAppliedIn(OTHER_SLOT));
    }

    @Test
    void repeatedSwitchesDoNotAccumulatePacks() {
        final UUID firstOffer = UUID.randomUUID();
        final long first = tracker.beginGeneration(player, "v3-lobby");
        select(first, Collections.singletonList(pending(SLOT, firstOffer, SHA_A)));
        tracker.onStatus(player, firstOffer, ManagedPackStatus.SUCCESSFULLY_LOADED);

        final UUID secondOffer = UUID.randomUUID();
        final long second = tracker.beginGeneration(player, "v3-arena");
        final List<UUID> superseded =
                tracker.setDesired(player, second, Collections.singletonList(pending(SLOT, secondOffer, SHA_B)),
                        true, FailurePolicy.KICK, 120_000L, new CompletableFuture<>());
        tracker.onStatus(player, secondOffer, ManagedPackStatus.SUCCESSFULLY_LOADED);

        assertEquals(Collections.singletonList(firstOffer), superseded,
                "the pack the slot used to hold must be handed back for removal");

        final PlayerPackState state = tracker.snapshot(player);
        assertEquals(1, state.desired().size());
        assertEquals(1, state.applied().size(), "one slot holds one pack, however many switches happened");
        assertEquals(secondOffer, state.applied().get(0).packId());

        // A third switch back to the original content behaves the same way.
        final UUID thirdOffer = UUID.randomUUID();
        final long third = tracker.beginGeneration(player, "v3-lobby");
        final List<UUID> supersededAgain =
                tracker.setDesired(player, third, Collections.singletonList(pending(SLOT, thirdOffer, SHA_A)),
                        true, FailurePolicy.KICK, 120_000L, new CompletableFuture<>());
        tracker.onStatus(player, thirdOffer, ManagedPackStatus.SUCCESSFULLY_LOADED);

        assertEquals(Collections.singletonList(secondOffer), supersededAgain);
        assertEquals(1, tracker.snapshot(player).applied().size());
    }

    @Test
    void foreignPacksAreNeverHandedBackForRemoval() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final List<UUID> superseded = tracker.setDesired(player, generation,
                Collections.singletonList(pending(SLOT, offer, SHA_A)),
                true, FailurePolicy.KICK, 120_000L, new CompletableFuture<>());

        // Nothing was tracked before this selection, so nothing may be removed from the client.
        assertTrue(superseded.isEmpty());
    }

    @Test
    void aReplyToASupersededOfferCannotCompleteTheNewOperation() {
        final UUID firstOffer = UUID.randomUUID();
        final long first = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> cancelled =
                select(first, Collections.singletonList(pending(SLOT, firstOffer, SHA_A)));

        final UUID secondOffer = UUID.randomUUID();
        final long second = tracker.beginGeneration(player, "v3-arena");
        final CompletableFuture<ApplyResult> current =
                select(second, Collections.singletonList(pending(SLOT, secondOffer, SHA_B)));

        assertTrue(cancelled.isDone(), "the superseded operation completes rather than leaking");
        assertEquals(ManagedPackStatus.CANCELLED, cancelled.join().terminalStatus());

        assertFalse(tracker.onStatus(player, firstOffer, ManagedPackStatus.SUCCESSFULLY_LOADED),
                "a late reply for the old offer must be ignored");
        assertFalse(tracker.request(player, firstOffer).isPresent());
        assertFalse(current.isDone());
        assertFalse(tracker.snapshot(player).isAppliedIn(SLOT));
    }

    @Test
    void reofferingTheSameContentGetsAFreshId() {
        final UUID preferred = UUID.randomUUID();
        assertEquals(preferred, tracker.allocateOfferId(player, preferred));
        final UUID second = tracker.allocateOfferId(player, preferred);
        assertNotEquals(preferred, second, "a re-offer must not reuse an id a late reply could match");
    }

    @Test
    void theTrackedRequestCarriesTheDescriptorAndPolicy() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        tracker.setDesired(player, generation, Collections.singletonList(pending(SLOT, offer, SHA_A)),
                false, FailurePolicy.RESTORE_PREVIOUS, 120_000L, new CompletableFuture<>());

        final PackStateTracker.Request request = tracker.request(player, offer).orElseThrow(AssertionError::new);
        assertEquals(SLOT, request.entry().logicalKey());
        assertEquals(SHA_A, request.entry().sha1());
        assertEquals(generation, request.generation());
        assertFalse(request.required());
        assertEquals(FailurePolicy.RESTORE_PREVIOUS, request.failurePolicy());
        assertEquals("v3-lobby", request.serverName().orElseThrow(AssertionError::new));

        // A client older than 1.20.3 sends no id, and can only hold the one pack.
        assertTrue(tracker.request(player, null).isPresent());
    }

    @Test
    void anOperationThatIsNeverAnsweredTimesOut() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> completion =
                select(generation, Collections.singletonList(pending(SLOT, offer, SHA_A)));

        clock.addAndGet(119_000L);
        assertTrue(tracker.expire().isEmpty());
        assertFalse(completion.isDone());

        clock.addAndGet(2_000L);
        assertEquals(Collections.singletonList(player), tracker.expire());

        final ApplyResult result = completion.join();
        assertFalse(result.success());
        assertEquals(ManagedPackStatus.TIMED_OUT, result.terminalStatus());
        assertTrue(tracker.expire().isEmpty(), "a timed out operation is not reported twice");
    }

    @Test
    void disconnectingCompletesTheOperation() {
        final UUID offer = UUID.randomUUID();
        final long generation = tracker.beginGeneration(player, "v3-lobby");
        final CompletableFuture<ApplyResult> completion =
                select(generation, Collections.singletonList(pending(SLOT, offer, SHA_A)));

        tracker.onDisconnect(player);

        assertEquals(ManagedPackStatus.CANCELLED, completion.join().terminalStatus());
        assertTrue(tracker.snapshot(player).isUnknown(), "a new connection starts from unknown");
    }

    @Test
    void anEmptySelectionRemovesWhatWasAppliedAndCompletes() {
        final UUID offer = UUID.randomUUID();
        final long first = tracker.beginGeneration(player, "v3-lobby");
        select(first, Collections.singletonList(pending(SLOT, offer, SHA_A)));
        tracker.onStatus(player, offer, ManagedPackStatus.SUCCESSFULLY_LOADED);

        final long second = tracker.beginGeneration(player, "v3-hub");
        final CompletableFuture<ApplyResult> completion = new CompletableFuture<>();
        final List<UUID> superseded = tracker.setDesired(player, second, new ArrayList<>(), true,
                FailurePolicy.KICK, 120_000L, completion);

        assertEquals(Collections.singletonList(offer), superseded);
        assertTrue(completion.join().success(), "having nothing to offer is not a failure");
        assertEquals(PackApplicationState.REMOVED, tracker.snapshot(player).aggregate());
        assertFalse(tracker.snapshot(player).isAppliedIn(SLOT),
                "readiness must stop as soon as the content is taken away");
    }

    @Test
    void snapshotsSupersedeInOrderAndReportRemoval() {
        final UUID offer = UUID.randomUUID();
        final long first = tracker.beginGeneration(player, "v3-lobby");
        select(first, Collections.singletonList(pending(SLOT, offer, SHA_A)));
        final PackStateSnapshot sent = tracker.wireSnapshot(player).orElseThrow(AssertionError::new);

        tracker.onStatus(player, offer, ManagedPackStatus.SUCCESSFULLY_LOADED);
        final PackStateSnapshot applied = tracker.wireSnapshot(player).orElseThrow(AssertionError::new);

        assertTrue(applied.supersedes(sent));
        assertFalse(sent.supersedes(applied));
        assertEquals(sent.sessionId(), applied.sessionId());
        assertTrue(applied.isAppliedIn(SLOT));

        final long second = tracker.beginGeneration(player, "v3-hub");
        tracker.setDesired(player, second, new ArrayList<>(), true, FailurePolicy.KICK, 120_000L,
                new CompletableFuture<>());
        final PackStateSnapshot removed = tracker.wireSnapshot(player).orElseThrow(AssertionError::new);

        assertTrue(removed.supersedes(applied));
        assertFalse(removed.isAppliedIn(SLOT));
        assertEquals(1, removed.entries().size(), "the slot that lost its pack is reported once");
        assertEquals(ManagedPackStatus.REMOVED, removed.entries().get(0).status());
    }

    @Test
    void aStaleGenerationNeverSendsAnything() {
        final long stale = tracker.beginGeneration(player, "v3-lobby");
        tracker.beginGeneration(player, "v3-arena");

        final CompletableFuture<ApplyResult> completion = new CompletableFuture<>();
        final List<UUID> superseded = tracker.setDesired(player, stale,
                Collections.singletonList(pending(SLOT, UUID.randomUUID(), SHA_A)),
                true, FailurePolicy.KICK, 120_000L, completion);

        assertTrue(superseded.isEmpty());
        assertEquals(ManagedPackStatus.CANCELLED, completion.join().terminalStatus());
        assertTrue(tracker.snapshot(player).desired().isEmpty(), "work for an old transition is dropped");
    }
}
