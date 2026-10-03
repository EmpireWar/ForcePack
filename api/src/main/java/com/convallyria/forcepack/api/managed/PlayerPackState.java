package com.convallyria.forcepack.api.managed;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Both halves of a player's managed pack state: what was selected for them, and what the
 * client actually has. Immutable.
 *
 * <p>Desired and applied are reported separately on purpose. Keeping a last-known-good
 * descriptor for rollback does not mean its content is still on the client, and an empty
 * desired list does not mean nothing is applied.</p>
 */
public final class PlayerPackState {

    private final UUID player;
    private final @Nullable String serverName;
    private final long generation;
    private final List<PackEntryState> desired;
    private final List<PackEntryState> applied;
    private final PackApplicationState aggregate;

    public PlayerPackState(UUID player,
                           @Nullable String serverName,
                           long generation,
                           List<PackEntryState> desired,
                           List<PackEntryState> applied,
                           PackApplicationState aggregate) {
        this.player = Objects.requireNonNull(player, "player");
        this.serverName = serverName;
        this.generation = generation;
        this.desired = Collections.unmodifiableList(new ArrayList<>(desired));
        this.applied = Collections.unmodifiableList(new ArrayList<>(applied));
        this.aggregate = Objects.requireNonNull(aggregate, "aggregate");
    }

    /**
     * @param player the player
     * @return a state carrying no information, for a player nothing is known about
     */
    public static PlayerPackState unknown(UUID player) {
        return new PlayerPackState(player, null, 0L, Collections.emptyList(),
                Collections.emptyList(), PackApplicationState.UNKNOWN);
    }

    public UUID player() {
        return player;
    }

    /**
     * @return the backend this state belongs to, empty during the configuration phase
     *         before a destination is committed
     */
    public Optional<String> serverName() {
        return Optional.ofNullable(serverName);
    }

    /**
     * @return the transition generation this state belongs to
     */
    public long generation() {
        return generation;
    }

    /**
     * @return the packs selected for this player
     */
    public List<PackEntryState> desired() {
        return desired;
    }

    /**
     * @return the packs the client is known to have applied
     */
    public List<PackEntryState> applied() {
        return applied;
    }

    public PackApplicationState aggregate() {
        return aggregate;
    }

    /**
     * Whether a pack occupying the given slot is applied on the client.
     *
     * <p>This is the check a downstream plugin should make. A success for an unrelated or
     * blank pack must not count as readiness for this slot.</p>
     *
     * @param logicalKey the slot to check, e.g. {@code battlegrounds:primary}
     * @return true if a pack in that slot is applied
     */
    public boolean isAppliedIn(String logicalKey) {
        Objects.requireNonNull(logicalKey, "logicalKey");
        for (PackEntryState entry : applied) {
            if (entry.logicalKey().equals(logicalKey) && entry.status().isApplied()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param logicalKey the slot to look up
     * @return the applied entry in that slot, if any
     */
    public Optional<PackEntryState> appliedIn(String logicalKey) {
        Objects.requireNonNull(logicalKey, "logicalKey");
        for (PackEntryState entry : applied) {
            if (entry.logicalKey().equals(logicalKey)) {
                return Optional.of(entry);
            }
        }
        return Optional.empty();
    }

    /**
     * @return true if nothing is known yet. Callers must not read this as either success
     *         or failure, and must not fall back to a stored flag from an earlier session.
     */
    public boolean isUnknown() {
        return aggregate == PackApplicationState.UNKNOWN;
    }

    @Override
    public String toString() {
        return "PlayerPackState{" + player + " on " + serverName + ", gen=" + generation
                + ", desired=" + desired.size() + ", applied=" + applied.size()
                + ", " + aggregate + '}';
    }
}
