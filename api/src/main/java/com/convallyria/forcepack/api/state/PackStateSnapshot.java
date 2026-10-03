package com.convallyria.forcepack.api.state;

import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The authoritative proxy view of one player's pack state, as sent to a backend. Immutable.
 *
 * <p>It deliberately carries no revision selector, release name or repository: the
 * component that chose the revision already knows those, and a backend does not need them
 * to decide whether the content it depends on is loaded.</p>
 *
 * <p>Session, generation and sequence exist so a backend can discard messages that a
 * transition has overtaken.</p>
 */
public final class PackStateSnapshot {

    /** The most entries one snapshot may carry. Keeps the payload bounded. */
    public static final int MAX_ENTRIES = 16;

    private final UUID player;
    private final UUID sessionId;
    private final long generation;
    private final long sequence;
    private final @Nullable String serverName;
    private final List<PackEntryState> entries;
    private final PackApplicationState aggregate;

    public PackStateSnapshot(UUID player,
                             UUID sessionId,
                             long generation,
                             long sequence,
                             @Nullable String serverName,
                             List<PackEntryState> entries,
                             PackApplicationState aggregate) {
        this.player = Objects.requireNonNull(player, "player");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.generation = generation;
        this.sequence = sequence;
        this.serverName = serverName;
        Objects.requireNonNull(entries, "entries");
        if (entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("snapshot carries " + entries.size()
                    + " entries, maximum is " + MAX_ENTRIES);
        }
        this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
    }

    public UUID player() {
        return player;
    }

    /**
     * Identifies the player's connection to the proxy. A new connection means a new
     * session, so state from an earlier one is never applied to this one.
     *
     * @return the session id
     */
    public UUID sessionId() {
        return sessionId;
    }

    /**
     * Increases on every transition, such as a server switch or a new application.
     *
     * @return the generation
     */
    public long generation() {
        return generation;
    }

    /**
     * Increases on every snapshot within a session. A snapshot whose sequence is not
     * greater than the last one applied is stale and must be discarded.
     *
     * @return the sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * @return the backend this state is for, empty when no destination is committed yet
     */
    public Optional<String> serverName() {
        return Optional.ofNullable(serverName);
    }

    /**
     * @return the per-pack state, including packs that are no longer applied
     */
    public List<PackEntryState> entries() {
        return entries;
    }

    public PackApplicationState aggregate() {
        return aggregate;
    }

    /**
     * @param logicalKey the slot to check
     * @return true if a pack in that slot is applied on the client
     */
    public boolean isAppliedIn(String logicalKey) {
        Objects.requireNonNull(logicalKey, "logicalKey");
        for (PackEntryState entry : entries) {
            if (entry.logicalKey().equals(logicalKey) && entry.status().isApplied()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this snapshot supersedes another for the same player.
     *
     * @param previous the last snapshot applied, or null if none
     * @return true if this one should be applied
     */
    public boolean supersedes(@Nullable PackStateSnapshot previous) {
        if (previous == null) return true;
        if (!previous.player.equals(player)) return false;
        if (!previous.sessionId.equals(sessionId)) {
            // A different connection entirely. Never carry state across sessions.
            return false;
        }
        if (generation != previous.generation) return generation > previous.generation;
        return sequence > previous.sequence;
    }

    @Override
    public String toString() {
        return "PackStateSnapshot{" + player + " on " + serverName + ", session=" + sessionId
                + ", gen=" + generation + ", seq=" + sequence
                + ", entries=" + entries.size() + ", " + aggregate + '}';
    }
}
