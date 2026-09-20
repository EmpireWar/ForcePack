package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.PackSource;
import com.convallyria.forcepack.api.managed.PreparedPack;
import com.convallyria.forcepack.api.resourcepack.ResourcePackVersion;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A validated {@link PackSource} with a client-downloadable URL.
 */
public final class PreparedManagedPack implements PreparedPack {

    private final PackSource source;
    private final String url;
    private final UUID packId;

    public PreparedManagedPack(PackSource source, String url) {
        this.source = Objects.requireNonNull(source, "source");
        this.url = Objects.requireNonNull(url, "url");
        // The same derivation the static packs use, so identical content keeps one identity.
        this.packId = UUID.nameUUIDFromBytes((url + source.sha1()).getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String logicalKey() {
        return source.logicalKey();
    }

    @Override
    public UUID packId() {
        return packId;
    }

    @Override
    public String url() {
        return url;
    }

    @Override
    public String sha1() {
        return source.sha1();
    }

    @Override
    public long sizeBytes() {
        return source.sizeBytes();
    }

    @Override
    public Optional<ResourcePackVersion> version() {
        return source.version();
    }

    @Override
    public PackSource source() {
        return source;
    }

    @Override
    public String toString() {
        return "PreparedManagedPack{" + logicalKey() + ", " + url + ", sha1=" + sha1() + '}';
    }
}
