package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.PreparedPack;
import com.convallyria.forcepack.api.resourcepack.ResourcePack;
import com.convallyria.forcepack.velocity.ForcePackVelocity;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One managed offer: a prepared pack plus the identity and policy of the request it belongs to.
 *
 * <p>Unlike a configured pack, the prompt, the forced-screen flag and the offer id come from
 * the request rather than from the profile's configuration. That is what lets a test offer be
 * recoverable while the configured production default stays required.</p>
 */
public final class ManagedResourcePack extends ResourcePack {

    private final ForcePackVelocity plugin;
    private final PreparedPack prepared;
    private final UUID offerId;
    private final @Nullable String prompt;
    private final boolean required;
    private final Consumer<Runnable> guard;

    public ManagedResourcePack(final ForcePackVelocity plugin,
                               final String server,
                               final PreparedPack prepared,
                               final UUID offerId,
                               final @Nullable String prompt,
                               final boolean required,
                               final Consumer<Runnable> guard) {
        super(plugin, server, prepared.url(), prepared.sha1(), toMegabytes(prepared.sizeBytes()),
                prepared.version().orElse(null));
        this.plugin = plugin;
        this.prepared = prepared;
        this.offerId = Objects.requireNonNull(offerId, "offerId");
        this.prompt = prompt;
        this.required = required;
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    private static int toMegabytes(long sizeBytes) {
        return (int) Math.min(Integer.MAX_VALUE, sizeBytes / 1024L / 1024L);
    }

    public PreparedPack getPrepared() {
        return prepared;
    }

    @Override
    public UUID getUUID() {
        return offerId;
    }

    @Override
    public void setResourcePack(UUID player) {
        final int delay = plugin.getConfig().getInt("delay-pack-sending-by");
        if (delay > 0) {
            plugin.getScheduler().executeDelayed(() -> guard.accept(() -> send(player)), delay);
        } else {
            guard.accept(() -> send(player));
        }
    }

    private void send(UUID uuid) {
        final Player player = plugin.getServer().getPlayer(uuid).orElse(null);
        if (player == null) {
            plugin.log("Not sending managed pack %s: %s is no longer connected.", offerId, uuid);
            return;
        }

        ResourcePackInfo.Builder builder = plugin.getServer()
                .createResourcePackBuilder(getURL())
                .setHash(getHashSum())
                .setId(offerId)
                .setShouldForce(required);
        if (prompt != null) {
            builder = builder.setPrompt(plugin.getMiniMessage().deserialize(prompt));
        }

        player.sendResourcePackOffer(builder.build());
        plugin.log("Sent managed pack %s (%s) to %s", prepared.logicalKey(), offerId, player.getUsername());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ManagedResourcePack)) return false;
        final ManagedResourcePack other = (ManagedResourcePack) o;
        // Offer identity, not content identity: re-offering the same bytes under a new id is a
        // different request, and the waiting set must hold both.
        return offerId.equals(other.offerId) && getServer().equals(other.getServer());
    }

    @Override
    public int hashCode() {
        return Objects.hash(offerId, getServer());
    }

    @Override
    public String toString() {
        return "ManagedResourcePack{" + prepared.logicalKey() + ", offer=" + offerId
                + ", sha1=" + getHash() + ", required=" + required + '}';
    }
}
