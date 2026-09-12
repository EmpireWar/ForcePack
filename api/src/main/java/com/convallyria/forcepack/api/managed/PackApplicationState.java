package com.convallyria.forcepack.api.managed;

/**
 * The aggregate result of a player's managed selection.
 *
 * <p>An empty waiting set means the operation finished, not that every required pack
 * loaded. {@link #PARTIAL} and {@link #FAILED} both describe finished operations.</p>
 */
public enum PackApplicationState {

    /** Nothing is known about this player yet. Never treat this as success or failure. */
    UNKNOWN,
    /** The player's profile selects no packs. */
    NONE,
    /** At least one pack is still waiting on the client. */
    PENDING,
    /** Every selected pack is applied. */
    APPLIED,
    /** Some selected packs applied and some did not. */
    PARTIAL,
    /** No selected pack applied. */
    FAILED,
    /** A previously applied selection was removed and nothing replaced it. */
    REMOVED
}
