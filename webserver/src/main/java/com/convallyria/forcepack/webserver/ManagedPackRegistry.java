package com.convallyria.forcepack.webserver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Content-indexed hosting for managed packs.
 *
 * <p>This is deliberately separate from the static configuration registry in
 * {@link ForcePackWebServer}. A reload clears the static registry; it must not be able to
 * take down a URL a player is currently downloading from, or one a prepared selection is
 * still pointing at.</p>
 *
 * <p>Immutability needs both a fixed path and fixed bytes, so registering copies the
 * source into a store file named after its own SHA-1. Verified content is reused; corrupted
 * content is repaired through atomic replacement. Different content produces a different
 * name. Rewriting the caller's source cannot change what a client downloads.</p>
 *
 * <p>Uses nothing outside the JDK on purpose, so it can be exercised without a web server.</p>
 */
public final class ManagedPackRegistry {

    /** The URL path prefix these packs are served under. */
    public static final String PATH_PREFIX = "/managed/";

    private static final String SUFFIX = ".zip";

    /** One hosted artifact. */
    public static final class Entry {

        private final String sha1;
        private final Path file;
        private final long sizeBytes;
        private volatile long lastUsedMillis;

        private Entry(String sha1, Path file, long sizeBytes, long lastUsedMillis) {
            this.sha1 = sha1;
            this.file = file;
            this.sizeBytes = sizeBytes;
            this.lastUsedMillis = lastUsedMillis;
        }

        /** @return the content identity, lowercase hex SHA-1 */
        public String sha1() {
            return sha1;
        }

        /** @return the immutable store file, which is named after {@link #sha1()} */
        public Path file() {
            return file;
        }

        public long sizeBytes() {
            return sizeBytes;
        }

        /** @return the path this artifact is served under, relative to the server root */
        public String servePath() {
            return PATH_PREFIX + sha1 + SUFFIX;
        }

        public long lastUsedMillis() {
            return lastUsedMillis;
        }
    }

    private final Path storeDirectory;
    private final LongSupplier clock;
    private final FileCopier copier;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @FunctionalInterface
    interface FileCopier {
        void copy(Path source, Path target) throws IOException;
    }

    public ManagedPackRegistry(Path storeDirectory) {
        this(storeDirectory, System::currentTimeMillis);
    }

    public ManagedPackRegistry(Path storeDirectory, LongSupplier clock) {
        this(storeDirectory, clock, (source, target) -> Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING));
    }

    ManagedPackRegistry(Path storeDirectory, LongSupplier clock, FileCopier copier) {
        this.storeDirectory = Objects.requireNonNull(storeDirectory, "storeDirectory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.copier = Objects.requireNonNull(copier, "copier");
    }

    /**
     * Hosts a file under its content identity.
     *
     * <p>The bytes are verified against the stated SHA-1 and size before anything is
     * published, and copied into the store so later edits to the source cannot change what
     * is served. Registering content that is already hosted is a no-op that returns the
     * existing entry.</p>
     *
     * @param source the file to host
     * @param sha1 the expected SHA-1 of that file, 40 hex characters
     * @param sizeBytes the expected size in bytes
     * @return the hosted entry
     * @throws IOException if the source cannot be read, or its bytes do not match
     */
    public synchronized Entry register(Path source, String sha1, long sizeBytes) throws IOException {
        Objects.requireNonNull(source, "source");
        final String id = normalise(sha1);
        final long now = clock.getAsLong();

        final Entry existing = entries.get(id);
        if (existing != null && matches(existing.file, id, sizeBytes)) {
            existing.lastUsedMillis = now;
            return existing;
        }
        if (existing != null) entries.remove(id, existing);

        final long actualSize = Files.size(source);
        if (actualSize != sizeBytes) {
            throw new IOException("size of " + source + " is " + actualSize + " bytes, expected " + sizeBytes);
        }
        final String actualSha1 = hash(source);
        if (!actualSha1.equals(id)) {
            throw new IOException("SHA-1 of " + source + " is " + actualSha1 + ", expected " + id);
        }

        Files.createDirectories(storeDirectory);
        final Path stored = storeDirectory.resolve(id + SUFFIX);
        if (!matches(stored, id, sizeBytes)) {
            entries.remove(id);
            // Publish by rename so a download can never observe a half-written file.
            final Path temporary = Files.createTempFile(storeDirectory, id, ".part");
            try {
                copier.copy(source, temporary);
                if (!matches(temporary, id, sizeBytes)) {
                    throw new IOException("Source changed while copying " + source + "; refusing to publish " + id);
                }
                // Fail closed on filesystems without atomic replacement. Never expose a partial ZIP.
                Files.move(temporary, stored, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }

        final Entry entry = new Entry(id, stored, sizeBytes, now);
        final Entry raced = entries.putIfAbsent(id, entry);
        if (raced != null) {
            raced.lastUsedMillis = now;
            return raced;
        }
        return entry;
    }

    /**
     * Looks up an artifact by the identifier in its URL.
     *
     * @param id either {@code <sha1>.zip} as it appears in the URL, or the bare SHA-1
     * @return the entry, or empty if nothing is hosted under that identity
     */
    public synchronized Optional<Entry> lookup(String id) {
        if (id == null) return Optional.empty();
        String key = id.toLowerCase(Locale.ROOT);
        if (key.endsWith(SUFFIX)) {
            key = key.substring(0, key.length() - SUFFIX.length());
        }
        final Entry entry = entries.get(key);
        if (entry == null) return Optional.empty();
        if (!Files.isRegularFile(entry.file)) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        entry.lastUsedMillis = clock.getAsLong();
        return Optional.of(entry);
    }

    /**
     * Removes artifacts nothing needs any more.
     *
     * <p>An artifact survives if the caller still wants it - a configured pin, an active or
     * pending request, a rollback candidate - or if it was registered or served inside the
     * grace period. Everything else is a development build that has aged out.</p>
     *
     * @param retain content identities to keep regardless of age
     * @param retentionMillis how long an unreferenced artifact stays after its last use
     * @return the number of artifacts removed
     */
    public synchronized int collect(Set<String> retain, long retentionMillis) {
        final Set<String> keep = new HashSet<>();
        for (String id : retain) {
            if (id != null) keep.add(id.toLowerCase(Locale.ROOT));
        }
        final long cutoff = clock.getAsLong() - Math.max(0L, retentionMillis);
        int removed = 0;
        for (Map.Entry<String, Entry> candidate : entries.entrySet()) {
            final Entry entry = candidate.getValue();
            if (keep.contains(candidate.getKey())) continue;
            if (entry.lastUsedMillis > cutoff) continue;
            if (!entries.remove(candidate.getKey(), entry)) continue;
            try {
                Files.deleteIfExists(entry.file);
            } catch (IOException ignored) {
                // The file stays on disk; it is no longer reachable, and the next collect retries.
            }
            removed++;
        }
        return removed;
    }

    /**
     * @return the content identities currently hosted
     */
    public Set<String> hosted() {
        return Collections.unmodifiableSet(new HashSet<>(entries.keySet()));
    }

    private static String normalise(String sha1) {
        Objects.requireNonNull(sha1, "sha1");
        final String lower = sha1.toLowerCase(Locale.ROOT);
        if (lower.length() != 40) {
            throw new IllegalArgumentException("sha1 must be 40 hex characters, got " + sha1.length());
        }
        for (int i = 0; i < 40; i++) {
            final char c = lower.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                throw new IllegalArgumentException("sha1 is not hexadecimal: " + sha1);
            }
        }
        return lower;
    }

    private static boolean matches(Path file, String sha1, long sizeBytes) throws IOException {
        return Files.isRegularFile(file) && Files.size(file) == sizeBytes && hash(file).equals(sha1);
    }

    private static String hash(Path file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
        final byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        final byte[] sum = digest.digest();
        final StringBuilder hex = new StringBuilder(sum.length * 2);
        for (byte b : sum) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
