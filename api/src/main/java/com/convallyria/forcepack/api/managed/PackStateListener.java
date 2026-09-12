package com.convallyria.forcepack.api.managed;

/**
 * Notified when a player's managed pack state changes.
 *
 * <p>Listeners are called after the state transition has been committed, so
 * {@link ManagedResourcePackService#snapshot(java.util.UUID)} already agrees with the
 * state passed here. They must not block.</p>
 */
@FunctionalInterface
public interface PackStateListener {

    /**
     * @param state the state after the change
     */
    void onStateChange(PlayerPackState state);
}
