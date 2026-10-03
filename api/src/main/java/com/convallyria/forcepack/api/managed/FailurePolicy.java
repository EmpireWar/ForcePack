package com.convallyria.forcepack.api.managed;

/**
 * What to do when a managed pack fails to apply.
 *
 * <p>This is per-request on purpose. A test offer must be recoverable without changing
 * the network-wide enforcement setting that the configured production default relies on.</p>
 */
public enum FailurePolicy {

    /** Disconnect the player. Appropriate for a required production pack. */
    KICK,
    /** Tell the player and leave the connection alone. */
    NOTIFY,
    /**
     * Reapply the last successfully applied selection and tell the player whether that
     * recovery was acknowledged. Appropriate for test offers.
     */
    RESTORE_PREVIOUS
}
