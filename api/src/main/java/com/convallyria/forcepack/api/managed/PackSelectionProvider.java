package com.convallyria.forcepack.api.managed;

import java.util.Collections;
import java.util.Collection;
import java.util.Set;

/**
 * Chooses which prepared packs a player receives on a managed profile.
 *
 * <p>Implementations must be memory-only and fast. This is called for joins, server
 * switches, reloads and {@link ManagedResourcePackService#refresh(java.util.UUID)}, on
 * paths that a client is waiting on. Resolve refs, wait on builds and download bytes in
 * {@link ManagedResourcePackService#prepare(PackSource)} beforehand, then publish the
 * result where this method can read it without blocking.</p>
 */
@FunctionalInterface
public interface PackSelectionProvider {

    /**
     * Selects the packs for one context.
     *
     * @param context why the selection is being computed
     * @param configured the profile's configured selection, which is what a provider with
     *                   nothing to say should return unchanged
     * @return the selection to offer, never null
     */
    PackSelection select(PackContext context, PackSelection configured);

    /**
     * The logical slots this provider owns. Two providers claiming the same slot on the
     * same profile is a configuration error and is rejected at registration, rather than
     * being resolved by whichever happened to register first.
     *
     * <p>An empty set means the provider claims no slot exclusively.</p>
     *
     * @return the owned slots
     */
    default Set<String> ownedKeys() {
        return Collections.emptySet();
    }

    /**
     * Prepared selections this provider may return even while no players are connected.
     * Hosting retains these packs until they disappear from this collection or the provider
     * unregisters. Return a thread-safe snapshot; this is called during garbage collection.
     *
     * @return configured defaults and other reusable selections
     */
    default Collection<PreparedPack> retainedPacks() {
        return Collections.emptyList();
    }

    /**
     * Deterministic ordering for providers that do not overlap. Higher runs later, so a
     * higher-priority provider sees the lower one as its {@code configured} argument.
     *
     * @return the priority
     */
    default int priority() {
        return 0;
    }
}
