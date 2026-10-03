package com.convallyria.forcepack.api.managed;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Managed resource-pack selection: prepared packs, tracked application, and durable
 * per-player state.
 *
 * <p>This exists because the static configuration path cannot express a selection that
 * changes per player at runtime. A caller prepares an artifact once, publishes it through
 * a {@link PackSelectionProvider}, and asks for a refresh; the platform owns the offer,
 * the enforcement, the correlation of replies and the reporting of state.</p>
 *
 * <p>Obtain an instance from the platform accessor,
 * {@link com.convallyria.forcepack.api.ForcePackAPI#getManagedService()}. Depend on this
 * module with {@code compileOnly}; do not shade a second copy of these classes.</p>
 */
public interface ManagedResourcePackService {

    /**
     * Registers a selection provider.
     *
     * @param owner an identifier for whoever is registering, used in diagnostics and slot
     *              conflict messages
     * @param provider the provider
     * @return a handle that removes the provider when closed
     * @throws IllegalStateException if another active provider already owns one of the
     *         provider's {@link PackSelectionProvider#ownedKeys() owned slots}
     */
    Registration registerProvider(String owner, PackSelectionProvider provider);

    /**
     * Validates a source and, if it needs hosting, registers it with the platform host.
     *
     * <p>This is the slow path: it may download and hash bytes. It never touches any
     * client. The result is reusable for any number of players, and preparing the same
     * source again returns an equivalent pack rather than re-hosting it.</p>
     *
     * @param source what to prepare
     * @return the prepared pack, or a stage completed exceptionally if the bytes do not
     *         match the stated SHA-1 and size, or cannot be reached
     */
    CompletionStage<PreparedPack> prepare(PackSource source);

    /**
     * Recomputes the player's selection and applies any difference.
     *
     * <p>The returned stage completes only when the operation has finished: a correlated
     * client acknowledgement, a terminal failure, a timeout, cancellation or disconnect.
     * Applying a selection the client already has still completes with confirmed state and
     * resynchronises the backend.</p>
     *
     * @param player the player
     * @return the outcome
     */
    CompletionStage<ApplyResult> refresh(UUID player);

    /**
     * Reads the current state without changing anything.
     *
     * @param player the player
     * @return the state, or {@link PlayerPackState#unknown(UUID)} if nothing is known.
     *         Never null.
     */
    PlayerPackState snapshot(UUID player);

    /**
     * Subscribes to state changes for every player.
     *
     * @param listener the listener
     * @return a handle that unsubscribes when closed
     */
    Registration subscribe(PackStateListener listener);
}
