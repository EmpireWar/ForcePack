package com.convallyria.forcepack.api.managed;

import java.util.Objects;
import java.util.UUID;

/**
 * The state of one pack in a player's selection. Immutable.
 */
public final class PackEntryState {

    private final String logicalKey;
    private final UUID packId;
    private final String sha1;
    private final ManagedPackStatus status;

    public PackEntryState(String logicalKey, UUID packId, String sha1, ManagedPackStatus status) {
        this.logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        this.packId = Objects.requireNonNull(packId, "packId");
        this.sha1 = Objects.requireNonNull(sha1, "sha1");
        this.status = Objects.requireNonNull(status, "status");
    }

    public String logicalKey() {
        return logicalKey;
    }

    /**
     * The offer identity. Keep this as the actual id sent to the client, even when the
     * content was reused by hash, so a reply can be correlated.
     *
     * @return the pack id
     */
    public UUID packId() {
        return packId;
    }

    /**
     * @return the content identity, lowercase hex SHA-1
     */
    public String sha1() {
        return sha1;
    }

    public ManagedPackStatus status() {
        return status;
    }

    public PackEntryState withStatus(ManagedPackStatus newStatus) {
        return new PackEntryState(logicalKey, packId, sha1, newStatus);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackEntryState)) return false;
        final PackEntryState other = (PackEntryState) o;
        return logicalKey.equals(other.logicalKey)
                && packId.equals(other.packId)
                && sha1.equals(other.sha1)
                && status == other.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalKey, packId, sha1, status);
    }

    @Override
    public String toString() {
        return "PackEntryState{" + logicalKey + ", " + packId + ", sha1=" + sha1 + ", " + status + '}';
    }
}
