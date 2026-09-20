package com.convallyria.forcepack.api.managed;

/**
 * A handle to something registered with the {@link ManagedResourcePackService}.
 *
 * <p>Closing a registration is idempotent.</p>
 */
public interface Registration extends AutoCloseable {

    /**
     * The owner that created this registration. Used in diagnostics and to reject
     * conflicting ownership of the same logical pack slot.
     *
     * @return the owner identifier supplied at registration time
     */
    String owner();

    /**
     * @return true if this registration is still active
     */
    boolean isActive();

    /**
     * Removes the registration. Calling this more than once has no further effect.
     */
    @Override
    void close();
}
