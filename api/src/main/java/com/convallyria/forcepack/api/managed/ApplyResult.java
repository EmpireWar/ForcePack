package com.convallyria.forcepack.api.managed;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one managed application attempt. Immutable.
 *
 * <p>Success means a correlated client load was acknowledged. Reaching a terminal status
 * is not success on its own.</p>
 */
public final class ApplyResult {

    private final boolean success;
    private final PlayerPackState state;
    private final ManagedPackStatus terminalStatus;
    private final @Nullable String failureReason;
    private final boolean recovered;

    public ApplyResult(boolean success,
                       PlayerPackState state,
                       ManagedPackStatus terminalStatus,
                       @Nullable String failureReason,
                       boolean recovered) {
        this.success = success;
        this.state = Objects.requireNonNull(state, "state");
        this.terminalStatus = Objects.requireNonNull(terminalStatus, "terminalStatus");
        this.failureReason = failureReason;
        this.recovered = recovered;
    }

    /**
     * @param state the state after application
     * @return a successful result
     */
    public static ApplyResult success(PlayerPackState state) {
        return new ApplyResult(true, state, ManagedPackStatus.SUCCESSFULLY_LOADED, null, false);
    }

    /**
     * @param state the state after the failure
     * @param terminalStatus the status that ended the attempt
     * @param reason a human-readable explanation
     * @param recovered whether the previous working selection was reapplied and acknowledged
     * @return a failed result
     */
    public static ApplyResult failure(PlayerPackState state,
                                      ManagedPackStatus terminalStatus,
                                      String reason,
                                      boolean recovered) {
        return new ApplyResult(false, state, terminalStatus, Objects.requireNonNull(reason, "reason"), recovered);
    }

    public boolean success() {
        return success;
    }

    /**
     * @return the player state after the attempt
     */
    public PlayerPackState state() {
        return state;
    }

    public ManagedPackStatus terminalStatus() {
        return terminalStatus;
    }

    public Optional<String> failureReason() {
        return Optional.ofNullable(failureReason);
    }

    /**
     * Whether the last successful selection was reapplied and the client acknowledged it.
     *
     * <p>Only report recovery to a user when this is true. A recovery attempt that was not
     * acknowledged leaves the client in an unknown state.</p>
     *
     * @return true if recovery was acknowledged
     */
    public boolean recovered() {
        return recovered;
    }

    @Override
    public String toString() {
        return "ApplyResult{" + (success ? "success" : "failure: " + failureReason)
                + ", " + terminalStatus + (recovered ? ", recovered" : "") + '}';
    }
}
