package com.convallyria.forcepack.sponge.listener;

import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackApplicationState;
import com.convallyria.forcepack.api.managed.PackEntryState;
import com.convallyria.forcepack.api.schedule.PlatformScheduler;
import com.convallyria.forcepack.api.state.PackStateSnapshot;
import com.convallyria.forcepack.sponge.ForcePackSponge;
import com.convallyria.forcepack.sponge.event.MultiVersionResourcePackStatusEvent;
import com.convallyria.forcepack.sponge.player.ForcePackSpongePlayer;
import com.convallyria.forcepack.sponge.state.SpongePackStateService;
import net.kyori.adventure.resource.ResourcePackStatus;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.spongepowered.api.entity.living.player.server.ServerPlayer;
import org.spongepowered.api.event.network.ServerSideConnectionEvent;
import org.spongepowered.api.network.ServerSideConnection;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.configurate.BasicConfigurationNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourcePackListenerTest {
    @Test
    void earlySuccessRemovedFromWaitingStillInitializesJoinAndLaterApplications() throws Exception {
        final UUID id = UUID.randomUUID();
        final UUID session = UUID.randomUUID();
        final ForcePackSponge plugin = mock(ForcePackSponge.class);
        final java.lang.reflect.Field exemptions = ForcePackSponge.class.getField("temporaryExemptedPlayers");
        exemptions.setAccessible(true);
        exemptions.set(plugin, new HashSet<UUID>());
        final BasicConfigurationNode config = BasicConfigurationNode.root();
        config.node("velocity-mode").set(true);
        when(plugin.getConfig()).thenReturn(config);
        doReturn(mock(PlatformScheduler.class)).when(plugin).getScheduler();
        final ServerSideConnection connection = mock(ServerSideConnection.class);
        final AtomicReference<ServerSideConnection> tracked = new AtomicReference<>();
        doAnswer(call -> { tracked.set(call.getArgument(1)); return null; }).when(plugin).trackConnection(eq(id), any());
        when(plugin.getConnection(id)).thenAnswer(call -> Optional.ofNullable(tracked.get()));
        final ForcePackSpongePlayer waiting = mock(ForcePackSpongePlayer.class);
        final AtomicReference<ForcePackSpongePlayer> pending = new AtomicReference<>();
        when(plugin.addToWaiting(eq(id), anySet())).thenAnswer(call -> { pending.set(waiting); return waiting; });
        when(plugin.getForcePackPlayer(id)).thenAnswer(call -> Optional.ofNullable(pending.get()));
        doAnswer(call -> { pending.set(null); return null; }).when(plugin).removeFromWaiting(id);
        final List<PackStateSnapshot> delivered = new ArrayList<>();
        final List<byte[]> messages = new ArrayList<>();
        final SpongePackStateService service = new SpongePackStateService((player, payload) -> {
            messages.add(payload); return true;
        }, player -> true, (format, args) -> { });
        service.subscribe(delivered::add);
        when(plugin.getPackStateService()).thenReturn(service);
        final ResourcePackListener listener = new ResourcePackListener(plugin);
        final GameProfile profile = mock(GameProfile.class);
        when(profile.uniqueId()).thenReturn(id);
        when(profile.name()).thenReturn(Optional.of("tester"));
        final ServerSideConnectionEvent.Auth auth = mock(ServerSideConnectionEvent.Auth.class);
        when(auth.profile()).thenReturn(profile);
        when(auth.connection()).thenReturn(connection);
        try (MockedStatic<ForcePackSpongePlayer> validity = mockStatic(ForcePackSpongePlayer.class)) {
            validity.when(() -> ForcePackSpongePlayer.profileIsValid(plugin, profile)).thenReturn(true);
            listener.onAuth(auth);
        }
        final ServerSideConnectionEvent.Configuration configuration = mock(ServerSideConnectionEvent.Configuration.class);
        when(configuration.profile()).thenReturn(profile);
        when(configuration.connection()).thenReturn(connection);
        listener.onConfig(configuration);

        final MultiVersionResourcePackStatusEvent success = mock(MultiVersionResourcePackStatusEvent.class);
        when(success.getProfile()).thenReturn(profile);
        when(success.getConnection()).thenReturn(connection);
        when(success.getID()).thenReturn(UUID.randomUUID());
        when(success.isProxy()).thenReturn(true);
        when(success.isProxyRemove()).thenReturn(true);
        when(success.getStatus()).thenReturn(ResourcePackStatus.SUCCESSFULLY_LOADED);
        service.accept(snapshot(id, session, 1));
        listener.onStatus(success);
        assertNull(pending.get());
        assertTrue(delivered.isEmpty());

        final ServerPlayer player = mock(ServerPlayer.class);
        when(player.uniqueId()).thenReturn(id);
        final ServerSideConnectionEvent.Join join = mock(ServerSideConnectionEvent.Join.class);
        when(join.player()).thenReturn(player);
        when(join.connection()).thenReturn(connection);
        listener.onPlayerJoin(join);
        assertEquals(1, delivered.size());
        assertEquals(2, messages.size(), "snapshot acknowledgement plus a join replay request");
        listener.onStatus(success); // A cached replay/later status still works without a waiting entry.
        service.accept(snapshot(id, session, 2));
        assertEquals(2, delivered.size());
        assertTrue(service.isAppliedIn(id, "siege:primary"));
    }

    private static PackStateSnapshot snapshot(UUID player, UUID session, long generation) {
        return new PackStateSnapshot(player, session, generation, generation, "siege",
                Collections.singletonList(new PackEntryState("siege:primary", UUID.randomUUID(), "a".repeat(40),
                        ManagedPackStatus.SUCCESSFULLY_LOADED)), PackApplicationState.APPLIED);
    }
}
