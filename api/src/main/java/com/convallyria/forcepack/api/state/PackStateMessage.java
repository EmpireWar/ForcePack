package com.convallyria.forcepack.api.state;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * One message on the {@code forcepack:state} channel. Immutable.
 *
 * <p>Three kinds, all on one channel so a version mismatch is detected once rather than
 * per message type.</p>
 */
public final class PackStateMessage {

    /**
     * The channel this schema belongs to. It is separate from {@code forcepack:status},
     * which has no version field and cannot gain one compatibly.
     */
    public static final String CHANNEL_NAMESPACE = "forcepack";

    /** The channel name within {@link #CHANNEL_NAMESPACE}. */
    public static final String CHANNEL_NAME = "state";

    /** The wire schema version. Increment on any incompatible payload change. */
    public static final int SCHEMA_VERSION = 1;

    public enum Kind {
        /** Backend to proxy: send me the authoritative state for this player. */
        REQUEST,
        /** Proxy to backend: here is the authoritative state. */
        SNAPSHOT,
        /** Backend to proxy: I applied the snapshot with this sequence. */
        ACK
    }

    private final Kind kind;
    private final @Nullable PackStateSnapshot snapshot;
    private final long ackSequence;

    private PackStateMessage(Kind kind, @Nullable PackStateSnapshot snapshot, long ackSequence) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.snapshot = snapshot;
        this.ackSequence = ackSequence;
    }

    public static PackStateMessage request() {
        return new PackStateMessage(Kind.REQUEST, null, 0L);
    }

    public static PackStateMessage snapshot(PackStateSnapshot snapshot) {
        return new PackStateMessage(Kind.SNAPSHOT, Objects.requireNonNull(snapshot, "snapshot"), 0L);
    }

    public static PackStateMessage ack(long sequence) {
        return new PackStateMessage(Kind.ACK, null, sequence);
    }

    public Kind kind() {
        return kind;
    }

    /**
     * @return the snapshot, present only for {@link Kind#SNAPSHOT}
     */
    public Optional<PackStateSnapshot> snapshot() {
        return Optional.ofNullable(snapshot);
    }

    /**
     * @return the acknowledged sequence, meaningful only for {@link Kind#ACK}
     */
    public long ackSequence() {
        return ackSequence;
    }

    @Override
    public String toString() {
        return "PackStateMessage{" + kind + (snapshot != null ? ", " + snapshot : "")
                + (kind == Kind.ACK ? ", seq=" + ackSequence : "") + '}';
    }
}
