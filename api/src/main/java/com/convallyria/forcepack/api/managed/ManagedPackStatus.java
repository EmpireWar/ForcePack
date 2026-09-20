package com.convallyria.forcepack.api.managed;

/**
 * The state of one managed pack for one player.
 *
 * <p>This deliberately does not reuse a platform status type: the api module must stay
 * free of platform and Adventure dependencies, and the managed lifecycle has states
 * that no client status packet expresses ({@link #PENDING}, {@link #TIMED_OUT},
 * {@link #CANCELLED}, {@link #REMOVED}).</p>
 *
 * <p>{@link #ACCEPTED} and {@link #DOWNLOADED} are progress, not success. Only
 * {@link #SUCCESSFULLY_LOADED} means the client has the content applied.</p>
 */
public enum ManagedPackStatus {

    /** Selected and registered, but the offer has not been sent yet. */
    PENDING,
    /** The offer has been sent to the client; no reply has arrived. */
    SENT,
    /** The client accepted the prompt. It has not downloaded or applied anything yet. */
    ACCEPTED,
    /** The client finished downloading. It has not applied anything yet. */
    DOWNLOADED,
    /** The client applied the content. This is the only success state. */
    SUCCESSFULLY_LOADED,
    /** The client declined the prompt. */
    DECLINED,
    /** The client could not download the content. */
    FAILED_DOWNLOAD,
    /** The client downloaded the content but could not load it. */
    FAILED_RELOAD,
    /** The client discarded the pack. */
    DISCARDED,
    /** The client rejected the URL. */
    INVALID_URL,
    /** The pack was removed from the client, or superseded by another selection. */
    REMOVED,
    /** No reply arrived within the configured client application bound. */
    TIMED_OUT,
    /** The operation was cancelled before it completed, e.g. by a server switch. */
    CANCELLED,
    /** No information is held for this pack. Never treat this as success or failure. */
    UNKNOWN;

    /**
     * @return true if this status is a terminal outcome, i.e. no further reply is expected
     */
    public boolean isTerminal() {
        switch (this) {
            case PENDING:
            case SENT:
            case ACCEPTED:
            case DOWNLOADED:
            case UNKNOWN:
                return false;
            default:
                return true;
        }
    }

    /**
     * @return true if the client has the content applied
     */
    public boolean isApplied() {
        return this == SUCCESSFULLY_LOADED;
    }
}
