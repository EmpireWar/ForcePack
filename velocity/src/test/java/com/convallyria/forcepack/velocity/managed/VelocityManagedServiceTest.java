package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.ApplyResult;
import com.convallyria.forcepack.api.managed.FailurePolicy;
import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.PackSelection;
import com.convallyria.forcepack.api.managed.PackSelectionProvider;
import com.convallyria.forcepack.api.managed.PackSource;
import com.convallyria.forcepack.api.managed.PreparedPack;
import com.convallyria.forcepack.api.managed.Registration;
import com.convallyria.forcepack.api.schedule.PlatformScheduler;
import com.convallyria.forcepack.velocity.ForcePackVelocity;
import com.convallyria.forcepack.velocity.config.VelocityConfig;
import com.convallyria.forcepack.velocity.handler.PackHandler;
import com.convallyria.forcepack.webserver.ManagedPackRegistry;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VelocityManagedServiceTest {
    private final ForcePackVelocity plugin = mock(ForcePackVelocity.class);
    private final ProxyServer proxy = mock(ProxyServer.class);
    private final PackHandler handler = mock(PackHandler.class);
    private final Player player = mock(Player.class);
    private final UUID id = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000L);
    private final AtomicReference<PackSelection> selected = new AtomicReference<>();
    private final List<ManagedResourcePack> offered = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private VelocityManagedService service;
    private ServerConnection siege;

    @BeforeEach
    void setUp() {
        final VelocityConfig config = mock(VelocityConfig.class);
        final VelocityConfig servers = mock(VelocityConfig.class);
        final VelocityConfig profile = mock(VelocityConfig.class);
        when(plugin.getConfig()).thenReturn(config);
        when(config.getConfig("servers")).thenReturn(servers);
        doReturn(Collections.singleton("siege")).when(servers).getKeys();
        when(servers.getConfig("siege")).thenReturn(profile);
        when(profile.getString("selection-provider")).thenReturn("quartermaster");
        when(profile.getBoolean("managed-required", true)).thenReturn(true);
        when(plugin.getLogger()).thenReturn(mock(Logger.class));
        when(plugin.getServer()).thenReturn(proxy);
        when(plugin.getPackHandler()).thenReturn(handler);
        when(proxy.getPlayer(id)).thenReturn(Optional.of(player));
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn("tester");
        when(player.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_3);
        when(player.getAppliedResourcePacks()).thenReturn(Collections.emptyList());
        siege = connect("siege");
        service = new VelocityManagedService(plugin, clock::get);
        service.reloadProfiles();
        doAnswer(call -> { offered.add(call.getArgument(1)); return null; })
                .when(handler).runSetPackTask(eq(player), any(ManagedResourcePack.class), anyInt());
        final PlatformScheduler<?> scheduler = mock(PlatformScheduler.class);
        doReturn(scheduler).when(plugin).getScheduler();
        when(config.getInt("delay-pack-sending-by")).thenReturn(5);
        doAnswer(call -> { delayed.add(call.getArgument(0)); return null; })
                .when(scheduler).executeDelayed(any(Runnable.class), anyLong());
        final ResourcePackInfo.Builder builder = mock(ResourcePackInfo.Builder.class, RETURNS_SELF);
        when(proxy.createResourcePackBuilder(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(mock(ResourcePackInfo.class));
    }

    private ServerConnection connect(String name) {
        final ServerConnection connection = mock(ServerConnection.class);
        when(connection.getServerInfo()).thenReturn(new ServerInfo(name, InetSocketAddress.createUnresolved("localhost", 25565)));
        when(player.getCurrentServer()).thenReturn(Optional.of(connection));
        return connection;
    }

    private PackSelection selection(char hash) {
        final String sha1 = String.valueOf(hash).repeat(40);
        final String url = "https://packs.example/" + sha1 + ".zip";
        final PreparedPack pack = new PreparedManagedPack(PackSource.remote("siege:primary", url, sha1, 8, null), url);
        return PackSelection.of(Collections.singletonList(pack), null, false, FailurePolicy.RESTORE_PREVIOUS);
    }

    private Registration register() {
        return service.registerProvider("quartermaster", (context, configured) -> selected.get());
    }

    private void succeedLatest() {
        assertTrue(service.getTracker().onStatus(id, offered.get(offered.size() - 1).getUUID(), ManagedPackStatus.SUCCESSFULLY_LOADED));
    }

    @Test
    void cachedTransferPublishesReadinessWithoutAnotherOffer() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        final ResourcePackInfo cached = mock(ResourcePackInfo.class);
        final byte[] hash = new byte[20];
        java.util.Arrays.fill(hash, (byte) 0xaa);
        when(cached.getHash()).thenReturn(hash);
        when(cached.getId()).thenReturn(offered.get(0).getUUID());
        when(player.getAppliedResourcePacks()).thenReturn(Collections.singletonList(cached));
        service.onBackendTransition(player);
        final ServerConnection destination = connect("siege");
        service.handleManagedServer(player, destination);
        assertEquals(1, offered.size());
        assertTrue(service.snapshot(id).isAppliedIn("siege:primary"));
        verify(destination).sendPluginMessage(eq(PackHandler.FORCEPACK_STATUS_IDENTIFIER), any(byte[].class));
    }

    @Test
    void configurationPhaseUsesItsDestinationBeforeCurrentBackendExists() {
        register();
        selected.set(selection('a'));
        when(player.getCurrentServer()).thenReturn(Optional.empty());
        when(handler.getConfigurationPhaseServer(player)).thenReturn(Optional.of(siege));
        service.handleManagedServer(player, siege);
        assertEquals(1, offered.size());
        succeedLatest();
        assertTrue(service.snapshot(id).isAppliedIn("siege:primary"));
    }

    @Test
    void overlappingRefreshCancelsWithoutRestoringOrInvalidatingNewest() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        selected.set(selection('b'));
        final CompletableFuture<ApplyResult> second = service.refresh(id).toCompletableFuture();
        selected.set(selection('c'));
        final CompletableFuture<ApplyResult> newest = service.refresh(id).toCompletableFuture();

        assertEquals(ManagedPackStatus.CANCELLED, second.join().terminalStatus());
        assertEquals(3, offered.size(), "cancellation must not offer A again");
        succeedLatest();
        assertTrue(newest.join().success());
        verify(player, never()).disconnect(any(Component.class));
    }

    @Test
    void currentDelayedOfferStillReachesItsPlayer() {
        register();
        selected.set(selection('a'));
        service.refresh(id);
        offered.get(0).setResourcePack(id);
        delayed.forEach(Runnable::run);
        verify(player).sendResourcePackOffer(any());
    }

    @Test
    void destinationChangeAloneBlocksSendsAndRecoveryBeforeTransitionCallback() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        selected.set(selection('b'));
        final CompletableFuture<ApplyResult> pending = service.refresh(id).toCompletableFuture();
        final ManagedResourcePack old = offered.get(1);
        old.setResourcePack(id);
        connect("epicquestz"); // Post-connect handling has not run yet.
        delayed.forEach(Runnable::run);
        assertFalse(service.acceptsStatus(player, service.getTracker().generation(id)));
        service.getTracker().onStatus(id, old.getUUID(), ManagedPackStatus.FAILED_DOWNLOAD);
        assertFalse(pending.join().recovered());
        assertEquals(2, offered.size());
        verify(player, never()).sendResourcePackOffer(any());
        verify(player, never()).disconnect(any(Component.class));
    }

    @Test
    void lateFailureAndDelayedSendCannotReachExcludedBackend() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        selected.set(selection('b'));
        final CompletableFuture<ApplyResult> pending = service.refresh(id).toCompletableFuture();
        final ManagedResourcePack old = offered.get(1);
        old.setResourcePack(id);
        final ServerConnection excluded = connect("epicquestz");
        assertFalse(service.handleManagedServer(player, excluded));
        delayed.forEach(Runnable::run);
        assertFalse(service.getTracker().onStatus(id, old.getUUID(), ManagedPackStatus.FAILED_DOWNLOAD));
        clock.addAndGet(200_000L);
        service.getTracker().expire();

        assertEquals(ManagedPackStatus.CANCELLED, pending.join().terminalStatus());
        assertEquals(2, offered.size());
        verify(player, never()).sendResourcePackOffer(any());
        verify(player, never()).disconnect(any(Component.class));
    }

    @Test
    void failedTransferInvalidatesOldWorkWhileFreshRefreshStillWorks() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        selected.set(selection('b'));
        final CompletableFuture<ApplyResult> old = service.refresh(id).toCompletableFuture();
        final UUID oldOffer = offered.get(1).getUUID();
        service.onBackendTransition(player); // Attempt fails: current backend stays Siege.
        assertEquals(ManagedPackStatus.CANCELLED, old.join().terminalStatus());
        assertFalse(service.getTracker().onStatus(id, oldOffer, ManagedPackStatus.FAILED_DOWNLOAD));
        final CompletableFuture<ApplyResult> next = service.refresh(id).toCompletableFuture();
        service.getTracker().onStatus(id, offered.get(2).getUUID(), ManagedPackStatus.FAILED_DOWNLOAD);
        assertEquals(selection('a').packs().get(0).sha1(), offered.get(3).getHash(), "failed transfer preserves recovery on the original backend");
        succeedLatest();
        assertTrue(next.join().recovered());
    }

    @Test
    void delayedSendCannotRunAfterTimeoutWithoutRecovery() {
        register();
        selected.set(selection('a').withPolicy(false, FailurePolicy.NOTIFY));
        final CompletableFuture<ApplyResult> pending = service.refresh(id).toCompletableFuture();
        final ManagedResourcePack pack = offered.get(0);
        pack.setResourcePack(id);
        clock.addAndGet(200_000L);
        service.getTracker().expire();
        assertEquals(ManagedPackStatus.TIMED_OUT, pending.join().terminalStatus());
        delayed.forEach(Runnable::run);
        assertFalse(service.getTracker().onStatus(id, pack.getUUID(), ManagedPackStatus.SUCCESSFULLY_LOADED));
        verify(player, never()).sendResourcePackOffer(any());
    }

    @Test
    void disconnectCancelsAndPreventsDelayedSendToReconnectedPlayer() {
        register();
        selected.set(selection('a'));
        final CompletableFuture<ApplyResult> pending = service.refresh(id).toCompletableFuture();
        offered.get(0).setResourcePack(id);
        service.onDisconnect(id);
        when(proxy.getPlayer(id)).thenReturn(Optional.of(mock(Player.class)));
        delayed.forEach(Runnable::run);
        assertEquals(ManagedPackStatus.CANCELLED, pending.join().terminalStatus());
        verify(player, never()).sendResourcePackOffer(any());
    }

    @Test
    void aRealClientFailureRestoresPreviousSelectionOnce() {
        register();
        selected.set(selection('a'));
        service.handleManagedServer(player, siege);
        succeedLatest();
        selected.set(selection('b'));
        final CompletableFuture<ApplyResult> result = service.refresh(id).toCompletableFuture();
        service.getTracker().onStatus(id, offered.get(1).getUUID(), ManagedPackStatus.FAILED_DOWNLOAD);
        assertEquals(3, offered.size());
        assertEquals(selection('a').packs().get(0).sha1(), offered.get(2).getHash());
        succeedLatest();
        assertTrue(result.join().recovered());
        assertFalse(result.join().success());
    }

    @Test
    void absentUnregisteredOrBrokenProviderRefusesAdmission() {
        assertTrue(service.handleManagedServer(player, siege));
        verify(player).disconnect(any(Component.class));
        clearInvocations(player);
        register().close();
        assertTrue(service.handleManagedServer(player, siege));
        verify(player).disconnect(any(Component.class));
        clearInvocations(player);
        final Registration broken = service.registerProvider("quartermaster", (context, configured) -> { throw new IllegalStateException("startup failed"); });
        service.handleManagedServer(player, siege);
        verify(player).disconnect(any(Component.class));
        broken.close();
        clearInvocations(player);
        selected.set(PackSelection.empty());
        register();
        service.handleManagedServer(player, siege);
        verify(player).disconnect(any(Component.class));
        clearInvocations(player);
        assertFalse(service.handleManagedServer(player, connect("epicquestz")));
        verify(player, never()).disconnect(any(Component.class));
    }

    @Test
    void idleProviderDefaultSurvivesCollectionUntilReleased(@TempDir Path directory) throws Exception {
        final Path source = directory.resolve("pack.zip");
        Files.writeString(source, "pack one");
        final byte[] bytes = Files.readAllBytes(source);
        final StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-1").digest(bytes)) hex.append(String.format("%02x", b));
        final String sha1 = hex.toString();
        final ManagedPackRegistry registry = new ManagedPackRegistry(directory.resolve("store"), clock::get);
        registry.register(source, sha1, bytes.length);
        final PreparedPack pack = new PreparedManagedPack(PackSource.local("siege:primary", source, sha1, bytes.length, null),
                "https://packs.example/managed/" + sha1 + ".zip");
        final Registration provider = service.registerProvider("quartermaster", new PackSelectionProvider() {
            @Override public PackSelection select(com.convallyria.forcepack.api.managed.PackContext context, PackSelection configured) {
                return PackSelection.of(Collections.singletonList(pack), null, true, FailurePolicy.KICK);
            }
            @Override public List<PreparedPack> retainedPacks() { return Collections.singletonList(pack); }
        });
        clock.addAndGet(100_000_000L);
        service.collectHostedContent(registry);
        assertArrayEquals(bytes, Files.readAllBytes(registry.lookup(sha1).orElseThrow().file()));
        service.handleManagedServer(player, siege);
        assertEquals(sha1, offered.get(0).getHash());
        service.onDisconnect(id);
        provider.close();
        clock.addAndGet(100_000_000L);
        service.collectHostedContent(registry);
        assertTrue(registry.lookup(sha1).isEmpty());
    }
}
