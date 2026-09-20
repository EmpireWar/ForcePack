package com.convallyria.forcepack.api.managed;

import com.convallyria.forcepack.api.resourcepack.ResourcePackVersion;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A request to prepare one pack for delivery.
 *
 * <p>A source is either remote (an immutable URL the client can already reach) or
 * managed-local (a file the platform must host itself). Either way the caller states the
 * expected SHA-1 and byte size up front: preparation verifies them rather than trusting
 * whatever the bytes turn out to be.</p>
 *
 * <p>The logical key names the slot this pack occupies, for example
 * {@code battlegrounds:primary}. Slot ownership is enforced per provider.</p>
 */
public final class PackSource {

    private final String logicalKey;
    private final @Nullable String url;
    private final @Nullable Path file;
    private final String sha1;
    private final long sizeBytes;
    private final @Nullable ResourcePackVersion version;

    private PackSource(String logicalKey,
                       @Nullable String url,
                       @Nullable Path file,
                       String sha1,
                       long sizeBytes,
                       @Nullable ResourcePackVersion version) {
        this.logicalKey = Objects.requireNonNull(logicalKey, "logicalKey");
        this.url = url;
        this.file = file;
        this.sha1 = requireSha1(sha1);
        if (sizeBytes <= 0) {
            throw new IllegalArgumentException("sizeBytes must be positive, got " + sizeBytes);
        }
        this.sizeBytes = sizeBytes;
        this.version = version;
    }

    /**
     * A pack the client downloads from an immutable URL that already exists.
     *
     * @param logicalKey the slot this pack occupies
     * @param url an immutable, credential-free URL
     * @param sha1 the expected SHA-1 of the bytes at that URL, as 40 hex characters
     * @param sizeBytes the expected size in bytes
     * @param version the client format range this pack supports, or null for any
     * @return the source
     */
    public static PackSource remote(String logicalKey,
                                    String url,
                                    String sha1,
                                    long sizeBytes,
                                    @Nullable ResourcePackVersion version) {
        Objects.requireNonNull(url, "url");
        if (url.indexOf('@') >= 0) {
            // Credentials belong in CI and proxy configuration, never in a URL sent to clients.
            throw new IllegalArgumentException("pack URL must not carry userinfo");
        }
        return new PackSource(logicalKey, url, null, sha1, sizeBytes, version);
    }

    /**
     * A pack the platform must host from an immutable local file.
     *
     * @param logicalKey the slot this pack occupies
     * @param file a file whose contents never change once published
     * @param sha1 the expected SHA-1 of that file, as 40 hex characters
     * @param sizeBytes the expected size in bytes
     * @param version the client format range this pack supports, or null for any
     * @return the source
     */
    public static PackSource local(String logicalKey,
                                   Path file,
                                   String sha1,
                                   long sizeBytes,
                                   @Nullable ResourcePackVersion version) {
        return new PackSource(logicalKey, null, Objects.requireNonNull(file, "file"), sha1, sizeBytes, version);
    }

    private static String requireSha1(String sha1) {
        Objects.requireNonNull(sha1, "sha1");
        final String lower = sha1.toLowerCase(java.util.Locale.ROOT);
        if (lower.length() != 40) {
            throw new IllegalArgumentException("sha1 must be 40 hex characters, got " + sha1.length());
        }
        for (int i = 0; i < 40; i++) {
            final char c = lower.charAt(i);
            final boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) throw new IllegalArgumentException("sha1 is not hexadecimal: " + sha1);
        }
        return lower;
    }

    public String logicalKey() {
        return logicalKey;
    }

    /**
     * @return the immutable remote URL, empty for a managed-local source
     */
    public Optional<String> url() {
        return Optional.ofNullable(url);
    }

    /**
     * @return the immutable local file to host, empty for a remote source
     */
    public Optional<Path> file() {
        return Optional.ofNullable(file);
    }

    /**
     * @return the expected SHA-1, lowercase hex. This is the content-cache identity.
     */
    public String sha1() {
        return sha1;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Optional<ResourcePackVersion> version() {
        return Optional.ofNullable(version);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PackSource)) return false;
        final PackSource other = (PackSource) o;
        return sizeBytes == other.sizeBytes
                && logicalKey.equals(other.logicalKey)
                && Objects.equals(url, other.url)
                && Objects.equals(file, other.file)
                && sha1.equals(other.sha1);
    }

    @Override
    public int hashCode() {
        return Objects.hash(logicalKey, url, file, sha1, sizeBytes);
    }

    @Override
    public String toString() {
        return "PackSource{" + logicalKey + ", " + (url != null ? url : String.valueOf(file))
                + ", sha1=" + sha1 + ", size=" + sizeBytes + '}';
    }
}
