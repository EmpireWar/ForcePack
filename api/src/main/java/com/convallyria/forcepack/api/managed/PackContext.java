package com.convallyria.forcepack.api.managed;

import java.util.Objects;
import java.util.UUID;

/**
 * Why a selection is being computed. Immutable, and safe to hold.
 *
 * <p>The context always names the backend the selection is <em>for</em>. During a server
 * switch that is the destination, not the server the player is still on.</p>
 */
public final class PackContext {

    private final UUID player;
    private final String serverName;
    private final int protocolVersion;
    private final double packFormat;
    private final long generation;
    private final boolean configurationPhase;

    public PackContext(UUID player,
                       String serverName,
                       int protocolVersion,
                       double packFormat,
                       long generation,
                       boolean configurationPhase) {
        this.player = Objects.requireNonNull(player, "player");
        this.serverName = Objects.requireNonNull(serverName, "serverName");
        this.protocolVersion = protocolVersion;
        this.packFormat = packFormat;
        this.generation = generation;
        this.configurationPhase = configurationPhase;
    }

    public UUID player() {
        return player;
    }

    /**
     * @return the backend this selection is for
     */
    public String serverName() {
        return serverName;
    }

    public int protocolVersion() {
        return protocolVersion;
    }

    /**
     * @return the client pack format, as resolved from the protocol version
     */
    public double packFormat() {
        return packFormat;
    }

    /**
     * The transition generation this selection belongs to. A provider should not hold work
     * across generations: anything prepared for an older generation is stale.
     *
     * @return the generation
     */
    public long generation() {
        return generation;
    }

    /**
     * @return true if the player is still in the configuration phase, so no backend
     *         player object exists yet
     */
    public boolean configurationPhase() {
        return configurationPhase;
    }

    @Override
    public String toString() {
        return "PackContext{" + player + " -> " + serverName + ", protocol=" + protocolVersion
                + ", format=" + packFormat + ", gen=" + generation
                + (configurationPhase ? ", config-phase" : "") + '}';
    }
}
