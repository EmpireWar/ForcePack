package com.convallyria.forcepack.api.state;

import com.convallyria.forcepack.api.managed.Registration;

import java.util.Optional;
import java.util.UUID;

/**
 * The backend view of proxy-authoritative pack state.
 *
 * <p>A backend plugin consumes this instead of listening to raw status events. Status
 * events arrive before a player object or profile exists, arrive more than once for the
 * same transition, and do not arrive at all when a server switch reuses a cached pack.
 * This service absorbs all three: it buffers configuration-phase state, replays it when
 * the backend player becomes usable, and discards messages a transition has overtaken.</p>
 *
 * <p>Implementations are registered by the platform. On Sponge, obtain it from the plugin
 * instance; depend on this module with {@code compileOnly} and guard on whether ForcePack
 * is installed.</p>
 */
public interface BackendPackStateService {

    /**
     * The last snapshot accepted for this player.
     *
     * <p>Empty means nothing is known yet, which is not the same as nothing being applied.
     * A caller must not substitute a value persisted from an earlier session.</p>
     *
     * @param player the player
     * @return the current snapshot, or empty if none has been accepted
     */
    Optional<PackStateSnapshot> current(UUID player);

    /**
     * Whether a pack occupying the given slot is applied for this player.
     *
     * @param player the player
     * @param logicalKey the slot to check, e.g. {@code battlegrounds:primary}
     * @return true only if a snapshot is held and it reports that slot as applied
     */
    default boolean isAppliedIn(UUID player, String logicalKey) {
        return current(player).map(snapshot -> snapshot.isAppliedIn(logicalKey)).orElse(false);
    }

    /**
     * Subscribes to accepted state changes.
     *
     * <p>Listeners are called only for snapshots that were accepted, so a stale or
     * duplicate message never reaches them. They are called once per accepted change,
     * which is what makes it safe to drive a kick, sound or title from them.</p>
     *
     * @param listener the listener
     * @return a handle that unsubscribes when closed
     */
    Registration subscribe(BackendPackStateListener listener);

    /**
     * Asks the proxy for the authoritative snapshot for this player.
     *
     * <p>Call this when a backend player becomes usable. It is a no-op when the platform
     * is not in proxy mode, or when the player has no proxy connection.</p>
     *
     * @param player the player
     */
    void requestSnapshot(UUID player);
}
