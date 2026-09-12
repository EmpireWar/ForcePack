package com.convallyria.forcepack.sponge.state;

import com.convallyria.forcepack.api.managed.Registration;
import com.convallyria.forcepack.api.state.BackendPackStateListener;
import com.convallyria.forcepack.api.state.BackendPackStateService;
import com.convallyria.forcepack.api.state.PackStateCodec;
import com.convallyria.forcepack.api.state.PackStateMessage;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * The backend side of {@code forcepack:state}.
 *
 * <p>One idempotent updater: every accepted snapshot goes through {@link #accept}, which
 * discards anything a transition has overtaken and notifies listeners once. That is what
 * makes it safe for a consumer to drive a kick, a sound or a title from a state change.</p>
 *
 * <p>State that arrives during the configuration phase is held until the backend player
 * exists, because a listener cannot do anything useful with it before then.</p>
 *
 * <p>Contains no Sponge types on purpose: sending and readiness are supplied by the plugin,
 * so the accept, discard and acknowledge rules can be tested without a server.</p>
 */
public final class SpongePackStateService implements BackendPackStateService {

    private static final class Session {

        private @Nullable PackStateSnapshot current;
        private @Nullable PackStateSnapshot undelivered;
        private boolean ready;
    }

    private static final class ListenerRegistration implements Registration {

        private final String owner;
        private final Runnable onClose;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private ListenerRegistration(String owner, Runnable onClose) {
            this.owner = owner;
            this.onClose = onClose;
        }

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public boolean isActive() {
            return active.get();
        }

        @Override
        public void close() {
            if (active.compareAndSet(true, false)) onClose.run();
        }
    }

    private final BiPredicate<UUID, byte[]> sender;
    private final Predicate<UUID> playerUsable;
    private final BiConsumer<String, Object[]> log;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<BackendPackStateListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * @param sender sends a payload on the player's proxy connection, returning false when
     *               there is no connection to send on
     * @param playerUsable whether the backend player object exists yet
     * @param log a debug log sink
     */
    public SpongePackStateService(BiPredicate<UUID, byte[]> sender,
                                  Predicate<UUID> playerUsable,
                                  BiConsumer<String, Object[]> log) {
        this.sender = Objects.requireNonNull(sender, "sender");
        this.playerUsable = Objects.requireNonNull(playerUsable, "playerUsable");
        this.log = Objects.requireNonNull(log, "log");
    }

    @Override
    public Optional<PackStateSnapshot> current(UUID player) {
        final Session session = sessions.get(player);
        return session == null ? Optional.empty() : Optional.ofNullable(session.current);
    }

    @Override
    public Registration subscribe(BackendPackStateListener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return new ListenerRegistration("subscriber", () -> listeners.remove(listener));
    }

    @Override
    public void requestSnapshot(UUID player) {
        Objects.requireNonNull(player, "player");
        if (!sender.test(player, PackStateCodec.encode(PackStateMessage.request()))) {
            log.accept("No proxy connection to request pack state for %s on.", new Object[] {player});
        }
    }

    /**
     * Handles one payload from the proxy.
     *
     * @param player the player whose connection carried it, as the server sees it
     * @param payload the raw bytes
     */
    public void handlePayload(UUID player, byte[] payload) {
        final PackStateMessage message;
        try {
            message = PackStateCodec.decode(payload);
        } catch (IllegalArgumentException malformed) {
            log.accept("Discarded a malformed pack state message: %s", new Object[] {malformed.getMessage()});
            return;
        }

        if (message.kind() != PackStateMessage.Kind.SNAPSHOT) {
            log.accept("Ignoring a %s on the backend side of the pack state channel.",
                    new Object[] {message.kind()});
            return;
        }

        final PackStateSnapshot snapshot = message.snapshot().orElse(null);
        if (snapshot == null) return;
        if (!snapshot.player().equals(player)) {
            // The connection identifies the player. A payload cannot claim to be about someone else.
            log.accept("Discarded a pack state snapshot for %s that arrived on %s's connection.",
                    new Object[] {snapshot.player(), player});
            return;
        }
        accept(snapshot);
    }

    /**
     * The one place state changes.
     *
     * @param snapshot the snapshot to apply
     * @return true if it was applied, false if a transition had already overtaken it
     */
    public boolean accept(PackStateSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final Session session = sessions.computeIfAbsent(snapshot.player(), player -> new Session());

        final boolean deliver;
        synchronized (session) {
            if (!snapshot.supersedes(session.current)) {
                log.accept("Discarded stale pack state for %s (generation %d, sequence %d).",
                        new Object[] {snapshot.player(), snapshot.generation(), snapshot.sequence()});
                return false;
            }
            session.current = snapshot;
            deliver = session.ready && playerUsable.test(snapshot.player());
            session.undelivered = deliver ? null : snapshot;
        }

        // Acknowledged whether or not a listener can act on it yet: the proxy is being told the
        // state arrived, not that something reacted to it.
        sender.test(snapshot.player(), PackStateCodec.encode(PackStateMessage.ack(snapshot.sequence())));
        if (deliver) notifyListeners(snapshot);
        return true;
    }

    /**
     * Marks the backend player usable, and releases anything buffered for it.
     *
     * @param player the player
     */
    public void markReady(UUID player) {
        final Session session = sessions.computeIfAbsent(player, id -> new Session());
        final PackStateSnapshot buffered;
        synchronized (session) {
            session.ready = true;
            buffered = session.undelivered;
            session.undelivered = null;
        }
        if (buffered != null) notifyListeners(buffered);
    }

    /**
     * Forgets a player. State never crosses connections.
     *
     * @param player the player
     */
    public void forget(UUID player) {
        sessions.remove(player);
    }

    private void notifyListeners(PackStateSnapshot snapshot) {
        for (BackendPackStateListener listener : listeners) {
            try {
                listener.onSnapshot(snapshot);
            } catch (RuntimeException failed) {
                log.accept("A pack state listener threw: %s", new Object[] {failed});
            }
        }
    }
}
