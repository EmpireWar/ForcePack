package com.convallyria.forcepack.api.managed;

import com.convallyria.forcepack.api.resourcepack.ResourcePackVersion;

import java.util.Optional;
import java.util.UUID;

/**
 * A {@link PackSource} that has been validated and, if it needed hosting, registered with
 * a host. It carries a client-downloadable URL and is reusable for any number of players.
 *
 * <p>Preparing is the slow part of delivery: it can resolve refs, wait on builds, download
 * and hash bytes. It happens entirely before a selection is published, never inside a
 * client event handler.</p>
 */
public interface PreparedPack {

    /**
     * @return the slot this pack occupies, e.g. {@code battlegrounds:primary}
     */
    String logicalKey();

    /**
     * The pack identity used for offers, status correlation and the backend snapshot.
     *
     * @return the stable pack id
     */
    UUID packId();

    /**
     * The URL a client can download this pack from. For a managed-local source this is the
     * endpoint the host registered, not the source file path.
     *
     * @return a client-downloadable URL
     */
    String url();

    /**
     * @return the verified SHA-1 of the delivered bytes, lowercase hex
     */
    String sha1();

    /**
     * @return the verified size in bytes
     */
    long sizeBytes();

    /**
     * @return the client format range this pack supports, empty for any
     */
    Optional<ResourcePackVersion> version();

    /**
     * @return the source this pack was prepared from
     */
    PackSource source();
}
