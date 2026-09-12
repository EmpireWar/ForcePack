package com.convallyria.forcepack.api.state;

/**
 * Notified when a backend accepts a new proxy snapshot for a player.
 *
 * <p>Every path that can change backend pack state feeds one idempotent updater, so a
 * native status event, a replayed snapshot after a server transfer and a fresh snapshot do
 * not each fire separately. A listener therefore does not need to deduplicate before
 * acting, and must not block.</p>
 */
@FunctionalInterface
public interface BackendPackStateListener {

    /**
     * @param snapshot the snapshot that was accepted
     */
    void onSnapshot(PackStateSnapshot snapshot);
}
