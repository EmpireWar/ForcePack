package com.convallyria.forcepack.api.state;

import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Encodes and decodes {@link PackStateMessage} for the {@code forcepack:state} channel.
 *
 * <p>Both sides use this class, so the proxy and the backend cannot drift apart. Decoding
 * is defensive: every length is bounded, every enum name is checked, and a payload from a
 * different schema version is rejected with a clear message instead of being parsed into
 * nonsense.</p>
 */
public final class PackStateCodec {

    /** Refuse anything larger than this. A legitimate message is far smaller. */
    public static final int MAX_PAYLOAD_BYTES = 8192;

    private PackStateCodec() {
    }

    /**
     * @param message the message to encode
     * @return the payload bytes
     */
    public static byte[] encode(PackStateMessage message) {
        Objects.requireNonNull(message, "message");
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(PackStateMessage.SCHEMA_VERSION);
            out.writeUTF(message.kind().name());
            switch (message.kind()) {
                case REQUEST:
                    break;
                case ACK:
                    out.writeLong(message.ackSequence());
                    break;
                case SNAPSHOT:
                    writeSnapshot(out, message.snapshot().orElseThrow(
                            () -> new IllegalArgumentException("SNAPSHOT message carries no snapshot")));
                    break;
                default:
                    throw new IllegalArgumentException("unhandled kind " + message.kind());
            }
        } catch (IOException e) {
            // A ByteArrayOutputStream does not do this.
            throw new UncheckedIOException(e);
        }
        final byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("encoded message is " + payload.length
                    + " bytes, maximum is " + MAX_PAYLOAD_BYTES);
        }
        return payload;
    }

    /**
     * @param payload the bytes received
     * @return the decoded message
     * @throws IllegalArgumentException if the payload is malformed, truncated, oversized,
     *         or written by an incompatible schema version
     */
    public static PackStateMessage decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload is " + payload.length
                    + " bytes, maximum is " + MAX_PAYLOAD_BYTES);
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            final int schema = in.readInt();
            if (schema != PackStateMessage.SCHEMA_VERSION) {
                throw new IllegalArgumentException("unsupported schema version " + schema
                        + ", this build speaks " + PackStateMessage.SCHEMA_VERSION);
            }
            final PackStateMessage.Kind kind = readEnum(PackStateMessage.Kind.class, in.readUTF());
            switch (kind) {
                case REQUEST:
                    return PackStateMessage.request();
                case ACK:
                    return PackStateMessage.ack(in.readLong());
                case SNAPSHOT:
                    return PackStateMessage.snapshot(readSnapshot(in));
                default:
                    throw new IllegalArgumentException("unhandled kind " + kind);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("truncated or malformed payload", e);
        }
    }

    private static void writeSnapshot(DataOutputStream out, PackStateSnapshot snapshot) throws IOException {
        writeUuid(out, snapshot.player());
        writeUuid(out, snapshot.sessionId());
        out.writeLong(snapshot.generation());
        out.writeLong(snapshot.sequence());
        out.writeUTF(snapshot.serverName().orElse(""));
        final List<PackEntryState> entries = snapshot.entries();
        out.writeInt(entries.size());
        for (PackEntryState entry : entries) {
            out.writeUTF(entry.logicalKey());
            writeUuid(out, entry.packId());
            out.writeUTF(entry.sha1());
            out.writeUTF(entry.status().name());
        }
        out.writeUTF(snapshot.aggregate().name());
    }

    private static PackStateSnapshot readSnapshot(DataInputStream in) throws IOException {
        final UUID player = readUuid(in);
        final UUID sessionId = readUuid(in);
        final long generation = in.readLong();
        final long sequence = in.readLong();
        final String serverName = in.readUTF();
        final int count = in.readInt();
        if (count < 0 || count > PackStateSnapshot.MAX_ENTRIES) {
            throw new IllegalArgumentException("entry count " + count + " is out of range 0.."
                    + PackStateSnapshot.MAX_ENTRIES);
        }
        final List<PackEntryState> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final String logicalKey = in.readUTF();
            final UUID packId = readUuid(in);
            final String sha1 = in.readUTF();
            final ManagedPackStatus status = readEnum(ManagedPackStatus.class, in.readUTF());
            entries.add(new PackEntryState(logicalKey, packId, sha1, status));
        }
        final PackApplicationState aggregate = readEnum(PackApplicationState.class, in.readUTF());
        return new PackStateSnapshot(player, sessionId, generation, sequence,
                serverName.isEmpty() ? null : serverName, entries, aggregate);
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        final long most = in.readLong();
        final long least = in.readLong();
        return new UUID(most, least);
    }

    private static <E extends Enum<E>> E readEnum(Class<E> type, String name) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equals(name)) return constant;
        }
        throw new IllegalArgumentException("unknown " + type.getSimpleName() + " '" + name + "'");
    }
}
