package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.ApplyResult;
import com.convallyria.forcepack.api.managed.FailurePolicy;
import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import com.convallyria.forcepack.api.managed.PackStateListener;
import com.convallyria.forcepack.api.managed.PlayerPackState;
import com.convallyria.forcepack.api.managed.Registration;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;

/**
 * The managed delivery state machine: one record per player connection, serialised.
 *
 * <p>This deliberately contains no proxy types. The lifecycle here - generations, stale
 * replies, timeouts, slot replacement, completion - is the part that has to be right, and
 * keeping it free of the platform is what makes it testable without a running proxy.</p>
 *
 * <p>Every operation completes exactly once, on acknowledgement, terminal failure, timeout,
 * cancellation or disconnect. The caller supplies the future so it can attach the failure
 * policy and the configuration-phase release to a single place.</p>
 */
public final class PackStateTracker {

    /** A tracked offer. Status handling reads this instead of re-deriving meaning from config. */
    public static final class Request {

        private final long generation;
        private final PackEntryState entry;
        private final boolean required;
        private final FailurePolicy failurePolicy;
        private final @Nullable String serverName;

        private Request(long generation, PackEntryState entry, boolean required,
                        FailurePolicy failurePolicy, @Nullable String serverName) {
            this.generation = generation;
            this.entry = entry;
            this.required = required;
            this.failurePolicy = failurePolicy;
            this.serverName = serverName;
        }

        public long generation() {
            return generation;
        }

        public PackEntryState entry() {
            return entry;
        }

        public boolean required() {
            return required;
        }

        public FailurePolicy failurePolicy() {
            return failurePolicy;
        }

        public Optional<String> serverName() {
            return Optional.ofNullable(serverName);
        }
    }

    private static final class Session {

        private final UUID player;
        private final UUID sessionId = UUID.randomUUID();
        private long generation;
        private long sequence;
        private @Nullable String serverName;

        /** What we want the client to have, by slot, in offer order. */
        private final Map<String, PackEntryState> desired = new LinkedHashMap<>();
        /** What the client is known to have, by slot. */
        private final Map<String, PackEntryState> applied = new LinkedHashMap<>();
        /** Slots this selection took away, reported once so a backend stops claiming readiness. */
        private final Map<String, PackEntryState> removed = new LinkedHashMap<>();
        /** The generation each offer id belongs to. A reply for an older one is stale. */
        private final Map<UUID, Long> offerGenerations = new LinkedHashMap<>();
        /** Offer ids already used on this connection, so a re-offer cannot collide with a late reply. */
        private final Set<UUID> usedOfferIds = new LinkedHashSet<>();

        private boolean required;
        private FailurePolicy failurePolicy = FailurePolicy.NOTIFY;
        private @Nullable CompletableFuture<ApplyResult> completion;
        private long deadlineMillis;
        private long operationGeneration;

        private Session(UUID player) {
            this.player = player;
        }
    }

    private final LongSupplier clockMillis;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final List<PackStateListener> listeners = new CopyOnWriteArrayList<>();

    public PackStateTracker(LongSupplier clockMillis) {
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    public Registration subscribe(PackStateListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return new CloseableRegistration("subscriber", () -> listeners.remove(listener));
    }

    /**
     * @param player the player
     * @return the id of this player's proxy connection, created if this is the first call
     */
    public UUID sessionId(UUID player) {
        return session(player).sessionId;
    }

    /**
     * Starts a new transition. Anything prepared for an older generation is stale from here on.
     *
     * @param player the player
     * @param serverName the backend this transition is for
     * @return the new generation
     */
    public long beginGeneration(UUID player, @Nullable String serverName) {
        final Session session = session(player);
        synchronized (session) {
            session.generation++;
            session.serverName = serverName;
            return session.generation;
        }
    }

    /**
     * @param player the player
     * @return the current generation
     */
    public long generation(UUID player) {
        final Session session = session(player);
        synchronized (session) {
            return session.generation;
        }
    }

    /**
     * Chooses the id to offer content under.
     *
     * <p>The prepared pack's own id is used when it is free. Re-offering the same content on
     * one connection gets a fresh id instead, so a late reply to the cancelled offer cannot
     * complete the new operation. The client still caches by SHA-1, so this costs no
     * download.</p>
     *
     * @param player the player
     * @param preferred the prepared pack's id
     * @return the id to send
     */
    public UUID allocateOfferId(UUID player, UUID preferred) {
        final Session session = session(player);
        synchronized (session) {
            UUID id = preferred;
            while (!session.usedOfferIds.add(id)) {
                id = UUID.randomUUID();
            }
            return id;
        }
    }

    /**
     * Registers the selection for a generation before anything is sent.
     *
     * @param player the player
     * @param generation the generation returned by {@link #beginGeneration(UUID, String)}
     * @param desired the packs to be offered, in offer order
     * @param required whether the client must accept
     * @param failurePolicy what to do if this selection fails
     * @param timeoutMillis how long the client has to apply it
     * @param completion completed exactly once with the outcome
     * @return the ids this selection supersedes, which the caller should remove from the client
     */
    public List<UUID> setDesired(UUID player,
                                 long generation,
                                 List<PackEntryState> desired,
                                 boolean required,
                                 FailurePolicy failurePolicy,
                                 long timeoutMillis,
                                 CompletableFuture<ApplyResult> completion) {
        Objects.requireNonNull(desired, "desired");
        Objects.requireNonNull(failurePolicy, "failurePolicy");
        Objects.requireNonNull(completion, "completion");

        final Session session = session(player);
        final List<UUID> superseded = new ArrayList<>();
        PlayerPackState published = null;
        synchronized (session) {
            if (generation != session.generation) {
                // A newer transition already started. Do not send anything for this one.
                completion.complete(ApplyResult.failure(state(session), ManagedPackStatus.CANCELLED,
                        "selection for generation " + generation + " was superseded", false));
                return superseded;
            }

            drainPending(session, ManagedPackStatus.CANCELLED, "superseded by a newer selection");

            final Set<UUID> keptIds = new LinkedHashSet<>();
            for (PackEntryState entry : desired) {
                keptIds.add(entry.packId());
            }

            session.removed.clear();
            for (PackEntryState previous : session.desired.values()) {
                if (!keptIds.contains(previous.packId())) {
                    superseded.add(previous.packId());
                }
            }
            for (PackEntryState previous : session.applied.values()) {
                if (!keptIds.contains(previous.packId()) && !superseded.contains(previous.packId())) {
                    superseded.add(previous.packId());
                }
            }
            for (PackEntryState previous : session.applied.values()) {
                if (!keptIds.contains(previous.packId())) {
                    session.removed.put(previous.logicalKey(), previous.withStatus(ManagedPackStatus.REMOVED));
                }
            }
            session.applied.values().removeIf(entry -> !keptIds.contains(entry.packId()));
            session.desired.clear();
            for (PackEntryState entry : desired) {
                session.desired.put(entry.logicalKey(), entry);
                session.offerGenerations.put(entry.packId(), generation);
                if (entry.status().isApplied()) {
                    session.applied.put(entry.logicalKey(), entry);
                }
            }

            session.required = required;
            session.failurePolicy = failurePolicy;
            session.completion = completion;
            session.operationGeneration = generation;
            session.deadlineMillis = clockMillis.getAsLong() + Math.max(0L, timeoutMillis);
            session.sequence++;
            published = state(session);
            checkCompletion(session);
        }
        publish(published);
        return superseded;
    }

    /**
     * Marks an offer as sent.
     *
     * @param player the player
     * @param packId the offer id
     */
    public void markSent(UUID player, UUID packId) {
        onStatus(player, packId, ManagedPackStatus.SENT);
    }

    /**
     * The tracked request a reply belongs to.
     *
     * @param player the player
     * @param packId the offer id the client replied about, or null for a legacy client that
     *               sends no id
     * @return the request, or empty if this reply belongs to no tracked managed offer
     */
    public Optional<Request> request(UUID player, @Nullable UUID packId) {
        final Session session = sessions.get(player);
        if (session == null) return Optional.empty();
        synchronized (session) {
            final PackEntryState entry = lookup(session, packId);
            if (entry == null) return Optional.empty();
            final Long offerGeneration = session.offerGenerations.get(entry.packId());
            if (offerGeneration == null || offerGeneration != session.generation) {
                // A reply to a superseded offer. It must not complete the current operation.
                return Optional.empty();
            }
            return Optional.of(new Request(session.generation, entry, session.required,
                    session.failurePolicy, session.serverName));
        }
    }

    /**
     * Applies a client reply.
     *
     * @param player the player
     * @param packId the offer id, or null for a legacy client
     * @param status the reported status
     * @return true if the reply belonged to a tracked offer and was applied
     */
    public boolean onStatus(UUID player, @Nullable UUID packId, ManagedPackStatus status) {
        Objects.requireNonNull(status, "status");
        final Session session = sessions.get(player);
        if (session == null) return false;
        PlayerPackState published = null;
        synchronized (session) {
            final PackEntryState entry = lookup(session, packId);
            if (entry == null) return false;
            final Long offerGeneration = session.offerGenerations.get(entry.packId());
            if (offerGeneration == null || offerGeneration != session.generation) return false;
            if (entry.status() == status) return true;
            if (entry.status().isTerminal() && status == ManagedPackStatus.SENT) return true;

            final PackEntryState updated = entry.withStatus(status);
            session.desired.put(updated.logicalKey(), updated);
            if (status.isApplied()) {
                session.applied.put(updated.logicalKey(), updated);
            } else {
                final PackEntryState current = session.applied.get(updated.logicalKey());
                if (current != null && current.packId().equals(updated.packId())) {
                    session.applied.remove(updated.logicalKey());
                }
            }
            session.sequence++;
            published = state(session);
            checkCompletion(session);
        }
        publish(published);
        return true;
    }

    /**
     * Cancels the operation in flight, for example because the player switched server.
     *
     * @param player the player
     * @param reason why
     */
    public void cancel(UUID player, String reason) {
        final Session session = sessions.get(player);
        if (session == null) return;
        PlayerPackState published = null;
        synchronized (session) {
            if (session.completion == null) return;
            for (Map.Entry<String, PackEntryState> entry : session.desired.entrySet()) {
                if (!entry.getValue().status().isTerminal()) {
                    entry.setValue(entry.getValue().withStatus(ManagedPackStatus.CANCELLED));
                }
            }
            session.sequence++;
            published = state(session);
            drainPending(session, ManagedPackStatus.CANCELLED, reason);
        }
        publish(published);
    }

    /**
     * Times out operations whose bound has passed.
     *
     * @return the players whose operation timed out
     */
    public List<UUID> expire() {
        final long now = clockMillis.getAsLong();
        final List<UUID> expired = new ArrayList<>();
        final List<PlayerPackState> published = new ArrayList<>();
        for (Session session : sessions.values()) {
            synchronized (session) {
                if (session.completion == null || session.deadlineMillis > now) continue;
                for (Map.Entry<String, PackEntryState> entry : session.desired.entrySet()) {
                    if (!entry.getValue().status().isTerminal()) {
                        entry.setValue(entry.getValue().withStatus(ManagedPackStatus.TIMED_OUT));
                    }
                }
                session.applied.values().removeIf(entry -> !entry.status().isApplied());
                session.sequence++;
                published.add(state(session));
                drainPending(session, ManagedPackStatus.TIMED_OUT, "the client did not reply in time");
                expired.add(session.player);
            }
        }
        for (PlayerPackState state : published) {
            publish(state);
        }
        return expired;
    }

    /**
     * Forgets a player. Any operation in flight completes as cancelled.
     *
     * @param player the player
     */
    public void onDisconnect(UUID player) {
        final Session session = sessions.remove(player);
        if (session == null) return;
        synchronized (session) {
            drainPending(session, ManagedPackStatus.CANCELLED, "the player disconnected");
        }
    }

    /**
     * @param player the player
     * @return the ids this player was offered and may still have applied
     */
    public Set<UUID> managedIds(UUID player) {
        final Session session = sessions.get(player);
        if (session == null) return new LinkedHashSet<>();
        synchronized (session) {
            return new LinkedHashSet<>(session.usedOfferIds);
        }
    }

    /**
     * @param player the player
     * @return the content identities this player's selection still depends on
     */
    public Set<String> retainedContent(UUID player) {
        final Session session = sessions.get(player);
        final Set<String> hashes = new LinkedHashSet<>();
        if (session == null) return hashes;
        synchronized (session) {
            for (PackEntryState entry : session.desired.values()) {
                hashes.add(entry.sha1());
            }
            for (PackEntryState entry : session.applied.values()) {
                hashes.add(entry.sha1());
            }
        }
        return hashes;
    }

    /**
     * @return every player with a tracked session
     */
    public Set<UUID> players() {
        return new LinkedHashSet<>(sessions.keySet());
    }

    public PlayerPackState snapshot(UUID player) {
        final Session session = sessions.get(player);
        if (session == null) return PlayerPackState.unknown(player);
        synchronized (session) {
            return state(session);
        }
    }

    /**
     * @param player the player
     * @return the state as it goes on the wire, or empty for a player with no session
     */
    public Optional<PackStateSnapshot> wireSnapshot(UUID player) {
        final Session session = sessions.get(player);
        if (session == null) return Optional.empty();
        synchronized (session) {
            final List<PackEntryState> entries = new ArrayList<>(session.desired.values());
            for (PackEntryState removed : session.removed.values()) {
                if (entries.size() >= PackStateSnapshot.MAX_ENTRIES) break;
                if (!session.desired.containsKey(removed.logicalKey())) entries.add(removed);
            }
            while (entries.size() > PackStateSnapshot.MAX_ENTRIES) {
                entries.remove(entries.size() - 1);
            }
            return Optional.of(new PackStateSnapshot(player, session.sessionId, session.generation,
                    session.sequence, session.serverName, entries, aggregate(session)));
        }
    }

    private Session session(UUID player) {
        Objects.requireNonNull(player, "player");
        return sessions.computeIfAbsent(player, Session::new);
    }

    private static @Nullable PackEntryState lookup(Session session, @Nullable UUID packId) {
        if (packId == null) {
            // A client older than 1.20.3 sends no id, and can only hold one pack.
            return session.desired.size() == 1 ? session.desired.values().iterator().next() : null;
        }
        for (PackEntryState entry : session.desired.values()) {
            if (entry.packId().equals(packId)) return entry;
        }
        return null;
    }

    private void checkCompletion(Session session) {
        if (session.completion == null) return;
        for (PackEntryState entry : session.desired.values()) {
            if (!entry.status().isTerminal()) return;
        }

        final PlayerPackState state = state(session);
        final CompletableFuture<ApplyResult> completion = session.completion;
        session.completion = null;
        if (state.aggregate() == PackApplicationState.APPLIED
                || state.aggregate() == PackApplicationState.NONE
                || state.aggregate() == PackApplicationState.REMOVED) {
            completion.complete(ApplyResult.success(state));
            return;
        }
        ManagedPackStatus worst = ManagedPackStatus.UNKNOWN;
        String reason = "the selection was not applied";
        for (PackEntryState entry : session.desired.values()) {
            if (!entry.status().isApplied()) {
                worst = entry.status();
                reason = entry.logicalKey() + " ended as " + entry.status();
                break;
            }
        }
        completion.complete(ApplyResult.failure(state, worst, reason, false));
    }

    private void drainPending(Session session, ManagedPackStatus status, String reason) {
        final CompletableFuture<ApplyResult> completion = session.completion;
        if (completion == null) return;
        session.completion = null;
        completion.complete(ApplyResult.failure(state(session), status, reason, false));
    }

    private static PlayerPackState state(Session session) {
        return new PlayerPackState(session.player, session.serverName, session.generation,
                new ArrayList<>(session.desired.values()), new ArrayList<>(session.applied.values()),
                aggregate(session));
    }

    private static PackApplicationState aggregate(Session session) {
        final Collection<PackEntryState> desired = session.desired.values();
        if (desired.isEmpty()) {
            if (!session.applied.isEmpty()) return PackApplicationState.APPLIED;
            return session.removed.isEmpty() ? PackApplicationState.NONE : PackApplicationState.REMOVED;
        }

        int applied = 0;
        for (PackEntryState entry : desired) {
            if (!entry.status().isTerminal()) return PackApplicationState.PENDING;
            if (entry.status().isApplied()) applied++;
        }
        if (applied == desired.size()) return PackApplicationState.APPLIED;
        return applied == 0 ? PackApplicationState.FAILED : PackApplicationState.PARTIAL;
    }

    private void publish(@Nullable PlayerPackState state) {
        if (state == null) return;
        for (PackStateListener listener : listeners) {
            listener.onStateChange(state);
        }
    }
}
