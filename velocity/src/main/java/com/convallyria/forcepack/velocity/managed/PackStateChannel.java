package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.PlayerPackState;
import com.convallyria.forcepack.api.state.PackStateCodec;
import com.convallyria.forcepack.api.state.PackStateMessage;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import com.convallyria.forcepack.velocity.ForcePackVelocity;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelMessageSource;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * The {@code forcepack:state} channel: authoritative pack state, proxy to backend.
 *
 * <p>This is additive. {@code forcepack:status} keeps relaying raw client statuses for
 * existing backends; a backend that speaks this channel gets versioned, ordered state that
 * survives a cached switch and a configuration-phase application.</p>
 */
public final class PackStateChannel {

    public static final MinecraftChannelIdentifier IDENTIFIER = MinecraftChannelIdentifier
            .create(PackStateMessage.CHANNEL_NAMESPACE, PackStateMessage.CHANNEL_NAME);

    private final ForcePackVelocity plugin;
    private final VelocityManagedService service;

    public PackStateChannel(final ForcePackVelocity plugin, final VelocityManagedService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void register() {
        plugin.getServer().getChannelRegistrar().register(IDENTIFIER);
        plugin.getServer().getEventManager().register(plugin, PluginMessageEvent.class, this::onPluginMessage);
        service.getTracker().subscribe(this::onStateChange);
    }

    private void onPluginMessage(PluginMessageEvent event) {
        if (!IDENTIFIER.equals(event.getIdentifier())) return;
        // Marked handled before the source is inspected. This channel is ours whoever sent the
        // message, and an unhandled message would be forwarded on to the other side.
        event.setResult(PluginMessageEvent.ForwardResult.handled());

        final ChannelMessageSource source = event.getSource();
        if (!(source instanceof ServerConnection)) {
            // A client can send on any channel. Nothing it says about pack state is evidence.
            plugin.log("Ignoring a forcepack:state message that did not come from a backend server.");
            return;
        }

        final ServerConnection connection = (ServerConnection) source;
        final Player player = connection.getPlayer();
        if (!isCurrentBackend(player, connection)) {
            plugin.log("Ignoring a forcepack:state message for %s from '%s', which is not their current backend.",
                    player.getUsername(), connection.getServerInfo().getName());
            return;
        }

        final PackStateMessage message;
        try {
            message = PackStateCodec.decode(event.getData());
        } catch (IllegalArgumentException malformed) {
            plugin.getLogger().warn("Discarded a malformed forcepack:state message from '{}': {}",
                    connection.getServerInfo().getName(), malformed.getMessage());
            return;
        }

        switch (message.kind()) {
            case REQUEST:
                sendSnapshot(player, connection);
                break;
            case ACK:
                plugin.log("%s acknowledged pack state sequence %d", player.getUsername(), message.ackSequence());
                break;
            case SNAPSHOT:
            default:
                plugin.getLogger().warn("Backend '{}' sent a pack state snapshot. The proxy is authoritative here.",
                        connection.getServerInfo().getName());
                break;
        }
    }

    private boolean isCurrentBackend(Player player, ServerConnection connection) {
        final ServerConnection current = player.getCurrentServer()
                .or(() -> plugin.getPackHandler().getConfigurationPhaseServer(player))
                .orElse(null);
        return current != null && current.getServerInfo().equals(connection.getServerInfo());
    }

    private void onStateChange(PlayerPackState state) {
        plugin.getServer().getPlayer(state.player()).ifPresent(player -> sendSnapshot(player, null));
    }

    private void sendSnapshot(Player player, @Nullable ServerConnection target) {
        final ServerConnection connection = target != null ? target : player.getCurrentServer()
                .or(() -> plugin.getPackHandler().getConfigurationPhaseServer(player))
                .orElse(null);
        if (connection == null) return;

        final PackStateSnapshot snapshot = service.getTracker().wireSnapshot(player.getUniqueId()).orElse(null);
        if (snapshot == null) return;

        // Always on the player's own connection.
        connection.sendPluginMessage(IDENTIFIER, PackStateCodec.encode(PackStateMessage.snapshot(snapshot)));
        plugin.log("Sent pack state sequence %d for %s to '%s'", snapshot.sequence(), player.getUsername(),
                connection.getServerInfo().getName());
    }
}
