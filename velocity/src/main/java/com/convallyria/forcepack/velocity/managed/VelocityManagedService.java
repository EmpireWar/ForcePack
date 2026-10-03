package com.convallyria.forcepack.velocity.managed;

import com.convallyria.forcepack.api.managed.ApplyResult;
import com.convallyria.forcepack.api.managed.FailurePolicy;
import com.convallyria.forcepack.api.managed.ManagedPackStatus;
import com.convallyria.forcepack.api.managed.ManagedResourcePackService;
import com.convallyria.forcepack.api.managed.PackContext;
import com.convallyria.forcepack.api.managed.PackEntryState;
import com.convallyria.forcepack.api.managed.PackSelection;
import com.convallyria.forcepack.api.managed.PackSelectionProvider;
import com.convallyria.forcepack.api.managed.PackSource;
import com.convallyria.forcepack.api.managed.PackStateListener;
import com.convallyria.forcepack.api.managed.PlayerPackState;
import com.convallyria.forcepack.api.managed.PreparedPack;
import com.convallyria.forcepack.api.managed.Registration;
import com.convallyria.forcepack.api.resourcepack.PackFormatResolver;
import com.convallyria.forcepack.velocity.ForcePackVelocity;
import com.convallyria.forcepack.velocity.config.VelocityConfig;
import com.convallyria.forcepack.velocity.handler.PackHandler;
import com.convallyria.forcepack.webserver.ForcePackWebServer;
import com.convallyria.forcepack.webserver.ManagedPackRegistry;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.player.ResourcePackInfo;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The Velocity implementation of managed selection.
 *
 * <p>Everything a managed profile offers goes through one tracked path: the selection is
 * computed, registered against a generation, and only then sent. Status handling reads the
 * tracked request rather than reconstructing meaning from the player's current server
 * configuration, which is what makes a per-player selection safe during a switch.</p>
 *
 * <p>Profiles without a {@code selection-provider} never reach this class.</p>
 */
public final class VelocityManagedService implements ManagedResourcePackService {

    private static final long DEFAULT_CLIENT_TIMEOUT_SECONDS = 120L;
    private static final long DEFAULT_PREPARE_TIMEOUT_SECONDS = 600L;
    private static final long DEFAULT_RETENTION_MINUTES = 60L * 24L;

    /** A server or group profile bound to a selection provider. */
    public static final class ManagedProfile {

        private final String rootName;
        private final String key;
        private final String providerOwner;
        private final boolean required;
        private final FailurePolicy failurePolicy;

        private ManagedProfile(String rootName, String key, String providerOwner,
                               boolean required, FailurePolicy failurePolicy) {
            this.rootName = rootName;
            this.key = key;
            this.providerOwner = providerOwner;
            this.required = required;
            this.failurePolicy = failurePolicy;
        }

        public String providerOwner() {
            return providerOwner;
        }

        @Override
        public String toString() {
            return rootName + "." + key + " -> " + providerOwner;
        }
    }

    private static final class ProviderEntry {

        private final String owner;
        private final PackSelectionProvider provider;

        private ProviderEntry(String owner, PackSelectionProvider provider) {
            this.owner = owner;
            this.provider = provider;
        }
    }

    private final ForcePackVelocity plugin;
    private final PackStateTracker tracker;
    private final List<ProviderEntry> providers = new CopyOnWriteArrayList<>();
    private final Map<String, ManagedProfile> profiles = new ConcurrentHashMap<>();
    private final Map<UUID, SuccessfulSelection> lastSuccessful = new ConcurrentHashMap<>();
    private final Map<UUID, Operation> operations = new ConcurrentHashMap<>();

    private static final class SuccessfulSelection {
        private final ServerConnection server;
        private final PackSelection selection;

        private SuccessfulSelection(ServerConnection server, PackSelection selection) {
            this.server = server;
            this.selection = selection;
        }
    }

    private static final class Operation {
        private final Player player;
        private final ServerConnection server;
        private final long generation;

        private Operation(Player player, ServerConnection server, long generation) {
            this.player = player;
            this.server = server;
            this.generation = generation;
        }
    }

    private boolean isCurrent(Operation operation) {
        final Player player = operation.player;
        final ServerConnection current = plugin.getPackHandler().getConfigurationPhaseServer(player)
                .or(player::getCurrentServer).orElse(null);
        return operations.get(player.getUniqueId()) == operation
                && plugin.getServer().getPlayer(player.getUniqueId()).orElse(null) == player
                && tracker.generation(player.getUniqueId()) == operation.generation
                && current == operation.server;
    }

    /** Invalidates work at the start of every backend attempt, even if the transfer later fails. */
    public void onBackendTransition(Player player) {
        synchronized (player) {
            operations.remove(player.getUniqueId());
            final PlayerPackState previous = tracker.snapshot(player.getUniqueId());
            tracker.cancel(player.getUniqueId(), "backend transition");
            releaseHolds(player.getUniqueId(), previous);
        }
    }

    /** True only for a reply to the current operation on this exact connection and backend. */
    public boolean acceptsStatus(Player player, long generation) {
        final Operation operation = operations.get(player.getUniqueId());
        return operation != null && operation.generation == generation && isCurrent(operation);
    }

    public VelocityManagedService(final ForcePackVelocity plugin) {
        this(plugin, System::currentTimeMillis);
    }

    VelocityManagedService(final ForcePackVelocity plugin, java.util.function.LongSupplier clock) {
        this.plugin = plugin;
        this.tracker = new PackStateTracker(clock);
    }

    public PackStateTracker getTracker() {
        return tracker;
    }

    /**
     * Starts the maintenance task: client timeouts, and collection of hosted content that
     * nothing points at any more.
     */
    public void start() {
        plugin.getServer().getScheduler().buildTask(plugin, this::maintain)
                .delay(5L, TimeUnit.SECONDS)
                .repeat(5L, TimeUnit.SECONDS)
                .schedule();
    }

    private void maintain() {
        for (UUID player : tracker.expire()) {
            plugin.getLogger().warn("Managed resource pack application for {} timed out.", player);
        }
        collectHostedContent();
    }

    // ------------------------------------------------------------------ providers

    @Override
    public synchronized Registration registerProvider(String owner, PackSelectionProvider provider) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(provider, "provider");
        for (String key : provider.ownedKeys()) {
            for (ProviderEntry existing : providers) {
                if (existing.provider.ownedKeys().contains(key)) {
                    throw new IllegalStateException("Pack slot '" + key + "' is already owned by '"
                            + existing.owner + "', so '" + owner + "' cannot claim it. "
                            + "Two providers owning one slot is a configuration error.");
                }
            }
        }

        final ProviderEntry entry = new ProviderEntry(owner, provider);
        providers.add(entry);
        plugin.getLogger().info("Registered managed selection provider '{}' (slots: {}).",
                owner, provider.ownedKeys());
        return new CloseableRegistration(owner, () -> providers.remove(entry));
    }

    private List<ProviderEntry> providersFor(ManagedProfile profile) {
        final List<ProviderEntry> matching = new ArrayList<>();
        for (ProviderEntry entry : providers) {
            if (entry.owner.equals(profile.providerOwner)) matching.add(entry);
        }
        matching.sort((left, right) -> Integer.compare(left.provider.priority(), right.provider.priority()));
        return matching;
    }

    // ------------------------------------------------------------------ profiles

    /**
     * Rereads which profiles are bound to a selection provider. Called on load and reload.
     */
    public void reloadProfiles() {
        profiles.clear();
        readProfiles("servers");
        readProfiles("groups");
        if (!profiles.isEmpty()) {
            plugin.getLogger().info("Managed profiles: {}", profiles.values());
        }
    }

    private void readProfiles(String rootName) {
        final VelocityConfig root = plugin.getConfig().getConfig(rootName);
        if (root == null) return;
        for (String key : root.getKeys()) {
            final VelocityConfig profileConfig = root.getConfig(key);
            if (profileConfig == null) continue;
            final String owner = profileConfig.getString("selection-provider");
            if (owner == null || owner.isEmpty()) continue;

            final ManagedProfile profile = new ManagedProfile(rootName, key, owner,
                    profileConfig.getBoolean("managed-required", true),
                    readPolicy(profileConfig.getString("managed-failure-policy")));
            for (String serverName : serversOf(rootName, key, profileConfig)) {
                final ManagedProfile clash = profiles.put(serverName, profile);
                if (clash != null) {
                    plugin.getLogger().error("Server '{}' is bound to more than one managed profile ({} and {}). "
                            + "Using {}.", serverName, clash, profile, profile);
                }
            }
        }
    }

    private FailurePolicy readPolicy(@Nullable String configured) {
        if (configured == null) return FailurePolicy.KICK;
        try {
            return FailurePolicy.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            plugin.getLogger().error("Unknown managed-failure-policy '{}'. Valid values are {}. Using KICK.",
                    configured, java.util.Arrays.toString(FailurePolicy.values()));
            return FailurePolicy.KICK;
        }
    }

    private Set<String> serversOf(String rootName, String key, VelocityConfig profileConfig) {
        if (!rootName.equals("groups")) return Collections.singleton(key);

        // Mirror the group membership rules the static path uses.
        final Set<String> members = new LinkedHashSet<>();
        final boolean exact = profileConfig.getBoolean("exact-match", true);
        for (String configured : profileConfig.getStringList("servers")) {
            for (RegisteredServer registered : plugin.getServer().getAllServers()) {
                final String name = registered.getServerInfo().getName();
                if (exact ? name.equals(configured) : name.contains(configured)) members.add(name);
            }
        }
        return members;
    }

    /**
     * @param serverName the backend
     * @return the managed profile for that backend, if it has one
     */
    public Optional<ManagedProfile> profileFor(String serverName) {
        return Optional.ofNullable(profiles.get(serverName));
    }

    // ------------------------------------------------------------------ delivery

    /**
     * Handles a connection or switch to a backend, if that backend is managed.
     *
     * @param player the player
     * @param server the backend being connected to
     * @return true if this backend is managed, so the static path must not also run
     */
    public boolean handleManagedServer(Player player, ServerConnection server) {
        synchronized (player) {
            final String serverName = server.getServerInfo().getName();
            final ManagedProfile profile = profiles.get(serverName);
            if (profile == null) {
                onBackendTransition(player);
                lastSuccessful.remove(player.getUniqueId());
                return false;
            }
            apply(player, server, profile);
            return true;
        }
    }

    private CompletionStage<ApplyResult> apply(Player player, ServerConnection server, ManagedProfile profile) {
        final UUID id = player.getUniqueId();
        final String serverName = server.getServerInfo().getName();
        lastSuccessful.computeIfPresent(id, (ignored, previous) -> previous.server == server ? previous : null);
        final List<ProviderEntry> matching = providersFor(profile);
        if (matching.isEmpty()) {
            plugin.getLogger().error("Server '{}' is bound to selection provider '{}', which is not registered. "
                            + "Refusing admission for {}; check that the provider plugin started successfully.",
                    serverName, profile.providerOwner, player.getUsername());
            operations.remove(id);
            tracker.cancel(id, "selection provider '" + profile.providerOwner + "' is not registered");
            player.disconnect(Component.text("Resource pack provider '" + profile.providerOwner
                    + "' is unavailable for " + serverName + ". Please contact an administrator."));
            return CompletableFuture.completedFuture(ApplyResult.failure(tracker.snapshot(id),
                    ManagedPackStatus.UNKNOWN, "selection provider '" + profile.providerOwner
                            + "' is not registered", false));
        }

        final long generation = tracker.beginGeneration(id, serverName);
        final int protocol = player.getProtocolVersion().getProtocol();
        final boolean configurationPhase = plugin.getPackHandler().getConfigurationPhaseServer(player).isPresent();
        final PackContext context = new PackContext(id, serverName, protocol,
                PackFormatResolver.getPackFormat(protocol), generation, configurationPhase);

        PackSelection selection = PackSelection.of(Collections.emptyList(), configuredPrompt(profile),
                profile.required, profile.failurePolicy);
        for (ProviderEntry entry : matching) {
            try {
                final PackSelection produced = entry.provider.select(context, selection);
                selection = Objects.requireNonNull(produced, "provider returned no selection");
            } catch (RuntimeException failed) {
                plugin.getLogger().error("Selection provider '{}' failed for {}; refusing admission.",
                        entry.owner, player.getUsername(), failed);
                return refuseSelection(player, "selection provider '" + entry.owner + "' failed");
            }
        }

        if (profile.required && selection.isEmpty()) return refuseSelection(player, "the managed provider has no prepared selection");

        return offer(player, server, profile, selection, generation);
    }

    private CompletionStage<ApplyResult> refuseSelection(Player player, String reason) {
        operations.remove(player.getUniqueId());
        tracker.cancel(player.getUniqueId(), reason);
        player.disconnect(Component.text("Resource pack unavailable: " + reason + ". Please contact an administrator."));
        return CompletableFuture.completedFuture(ApplyResult.failure(tracker.snapshot(player.getUniqueId()),
                ManagedPackStatus.UNKNOWN, reason, false));
    }

    private CompletionStage<ApplyResult> offer(Player player,
                                               ServerConnection server,
                                               ManagedProfile profile,
                                               PackSelection selection,
                                               long generation) {
        final UUID id = player.getUniqueId();
        final int protocol = player.getProtocolVersion().getProtocol();
        final boolean modern = protocol >= ProtocolVersion.MINECRAFT_1_20_3.getProtocol();
        final String prompt = selection.prompt().orElse(configuredPrompt(profile));
        final Operation operation = new Operation(player, server, generation);
        operations.put(id, operation);

        final List<PackEntryState> desired = new ArrayList<>();
        final List<ManagedResourcePack> toSend = new ArrayList<>();
        final List<UUID> reused = new ArrayList<>();
        for (PreparedPack pack : selection.packs()) {
            final UUID appliedId = appliedIdForHash(player, pack.sha1());
            if (appliedId != null) {
                // Content the client already has. Keep its real id so a later reply correlates.
                desired.add(new PackEntryState(pack.logicalKey(), appliedId, pack.sha1(),
                        ManagedPackStatus.SUCCESSFULLY_LOADED));
                reused.add(appliedId);
                continue;
            }
            final UUID offerId = tracker.allocateOfferId(id, pack.packId());
            desired.add(new PackEntryState(pack.logicalKey(), offerId, pack.sha1(), ManagedPackStatus.PENDING));
            toSend.add(new ManagedResourcePack(plugin, server.getServerInfo().getName(), pack,
                    offerId, prompt, selection.required(), send -> {
                        synchronized (player) {
                            if (isCurrent(operation) && tracker.request(id, offerId)
                                    .filter(request -> !request.entry().status().isTerminal()).isPresent()) send.run();
                        }
                    }));
        }

        final CompletableFuture<ApplyResult> completion = new CompletableFuture<>();
        // Registered before anything is sent, so a reply can never arrive before we track it.
        final List<UUID> superseded = tracker.setDesired(id, generation, desired, selection.required(),
                selection.failurePolicy(), clientTimeoutMillis(), completion);
        if (!isCurrent(operation)) return completion;

        if (modern) {
            for (UUID old : superseded) {
                plugin.log("Removing superseded managed pack %s from %s", old, player.getUsername());
                player.removeResourcePacks(old);
            }
        }

        for (ManagedResourcePack pack : toSend) {
            plugin.getPackHandler().runSetPackTask(player, pack, protocol);
            tracker.markSent(id, pack.getUUID());
        }
        for (UUID old : superseded) plugin.getPackHandler().processWaitingResourcePack(player, old);

        for (UUID reusedId : reused) {
            // The static path tells the backend about a pack it did not have to re-send. Do the
            // same here so a cached switch still reaches the backend.
            server.sendPluginMessage(PackHandler.FORCEPACK_STATUS_IDENTIFIER,
                    (reusedId + ";SUCCESSFULLY_LOADED;" + !plugin.getPackHandler().isWaiting(player))
                            .getBytes(StandardCharsets.UTF_8));
        }

        return completion.thenCompose(result -> {
            synchronized (player) {
                return finish(operation, profile, selection, result);
            }
        });
    }

    private CompletionStage<ApplyResult> finish(Operation operation,
                                                ManagedProfile profile,
                                                PackSelection selection,
                                                ApplyResult result) {
        final UUID id = operation.player.getUniqueId();
        if (result.terminalStatus() == ManagedPackStatus.CANCELLED || !isCurrent(operation)) {
            return CompletableFuture.completedFuture(result);
        }
        releaseHolds(id, result.state());
        if (result.success()) {
            if (!selection.isEmpty()) lastSuccessful.put(id, new SuccessfulSelection(operation.server, selection));
            return CompletableFuture.completedFuture(result);
        }

        plugin.getLogger().warn("Managed resource pack application failed for {}: {}", id,
                result.failureReason().orElse(result.terminalStatus().name()));

        switch (selection.failurePolicy()) {
            case KICK:
                message(id, profile, result.terminalStatus(), true);
                return CompletableFuture.completedFuture(result);
            case RESTORE_PREVIOUS:
                return restore(operation, profile, selection, result);
            case NOTIFY:
            default:
                message(id, profile, result.terminalStatus(), false);
                return CompletableFuture.completedFuture(result);
        }
    }

    private CompletionStage<ApplyResult> restore(Operation operation,
                                                 ManagedProfile profile,
                                                 PackSelection failed,
                                                 ApplyResult result) {
        final UUID id = operation.player.getUniqueId();
        if (!isCurrent(operation)) return CompletableFuture.completedFuture(result);
        final SuccessfulSelection successful = lastSuccessful.get(id);
        final PackSelection previous = successful == null || successful.server != operation.server ? null : successful.selection;
        final Player player = operation.player;
        final ServerConnection server = operation.server;
        if (previous == null || sameContent(previous, failed)) {
            message(id, profile, result.terminalStatus(), false);
            return CompletableFuture.completedFuture(result);
        }

        plugin.log("Restoring the last working selection for %s after %s", player.getUsername(),
                result.terminalStatus());
        // One attempt, and never recursive: recovery itself only notifies.
        final PackSelection recovery = previous.withPolicy(previous.required(), FailurePolicy.NOTIFY);
        final long generation = tracker.beginGeneration(id, server.getServerInfo().getName());
        return offer(player, server, profile, recovery, generation)
                .thenApply(recovered -> ApplyResult.failure(recovered.state(), result.terminalStatus(),
                        result.failureReason().orElse("the selection was not applied"), recovered.success()));
    }

    private static boolean sameContent(PackSelection left, PackSelection right) {
        if (left.packs().size() != right.packs().size()) return false;
        for (int i = 0; i < left.packs().size(); i++) {
            if (!left.packs().get(i).sha1().equals(right.packs().get(i).sha1())) return false;
        }
        return true;
    }

    private void releaseHolds(UUID id, PlayerPackState state) {
        final Player player = plugin.getServer().getPlayer(id).orElse(null);
        if (player == null) return;
        for (PackEntryState entry : state.desired()) {
            plugin.getPackHandler().processWaitingResourcePack(player, entry.packId());
        }
    }

    private void message(UUID id, ManagedProfile profile, ManagedPackStatus status, boolean kick) {
        final Player player = plugin.getServer().getPlayer(id).orElse(null);
        if (player == null) return;

        final VelocityConfig profileConfig = profileConfig(profile);
        final VelocityConfig actions = profileConfig == null ? null : profileConfig.getConfig("actions");
        final VelocityConfig action = actions == null ? null : actions.getConfig(status.name());
        final String text = action == null ? null : action.getString("message");
        final Component component = text != null
                ? plugin.getMiniMessage().deserialize(text)
                : Component.text("The resource pack could not be applied (" + status.name() + ").");
        if (kick) {
            player.disconnect(component);
        } else {
            player.sendMessage(component);
        }
    }

    /**
     * @param serverName the backend
     * @return the configuration node of that backend's managed profile, if it has one
     */
    public Optional<VelocityConfig> profileConfigFor(String serverName) {
        final ManagedProfile profile = profiles.get(serverName);
        return profile == null ? Optional.empty() : Optional.ofNullable(profileConfig(profile));
    }

    private @Nullable VelocityConfig profileConfig(ManagedProfile profile) {
        final VelocityConfig root = plugin.getConfig().getConfig(profile.rootName);
        return root == null ? null : root.getConfig(profile.key);
    }

    private @Nullable String configuredPrompt(ManagedProfile profile) {
        final VelocityConfig profileConfig = profileConfig(profile);
        final VelocityConfig resourcePack = profileConfig == null ? null : profileConfig.getConfig("resourcepack");
        return resourcePack == null ? null : resourcePack.getString("prompt");
    }

    private static @Nullable UUID appliedIdForHash(Player player, String sha1) {
        for (ResourcePackInfo applied : player.getAppliedResourcePacks()) {
            final byte[] hash = applied.getHash();
            if (hash == null) continue;
            if (toHex(hash).equals(sha1)) return applied.getId();
        }
        return null;
    }

    private static String toHex(byte[] bytes) {
        final StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    /**
     * Cancels anything in flight for a player who left.
     *
     * @param id the player
     */
    public void onDisconnect(UUID id) {
        final Operation operation = operations.get(id);
        final Object lock = operation == null ? this : operation.player;
        synchronized (lock) {
            operations.remove(id);
            tracker.onDisconnect(id);
            lastSuccessful.remove(id);
        }
    }

    // ------------------------------------------------------------------ api

    @Override
    public CompletionStage<PreparedPack> prepare(PackSource source) {
        Objects.requireNonNull(source, "source");
        // Re-register even cached content: this verifies hosting and renews its grace period
        // before the caller can publish it as a newly retained default.

        final CompletableFuture<PreparedPack> future = new CompletableFuture<>();
        plugin.getScheduler().executeAsync(() -> {
            try {
                future.complete(doPrepare(source));
            } catch (Exception failed) {
                future.completeExceptionally(failed);
            }
        });
        return future.orTimeout(prepareTimeoutSeconds(), TimeUnit.SECONDS);
    }

    private PreparedPack doPrepare(PackSource source) throws IOException {
        final String url;
        final Path file = source.file().orElse(null);
        if (file != null) {
            final ForcePackWebServer webServer = plugin.getWebServer().orElseThrow(() -> new IllegalStateException(
                    "Cannot host managed pack " + source.logicalKey()
                            + " because the embedded web server is disabled. Enable [web-server] or supply a remote URL."));
            url = webServer.hostManagedPack(file, source.sha1(), source.sizeBytes());
        } else {
            url = source.url().orElseThrow(() -> new IllegalStateException("PackSource has neither a URL nor a file"));
            verifyRemote(source, url);
        }

        final PreparedPack pack = new PreparedManagedPack(source, url);
        plugin.log("Prepared managed pack %s", pack);
        return pack;
    }

    private static void verifyRemote(PackSource source, String url) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }

        long size = 0L;
        final URLConnection connection = new URL(url).openConnection();
        try (InputStream in = connection.getInputStream()) {
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
                size += read;
            }
        }

        final String actual = toHex(digest.digest());
        if (!actual.equals(source.sha1())) {
            throw new IOException("SHA-1 of " + url + " is " + actual + ", expected " + source.sha1());
        }
        if (size != source.sizeBytes()) {
            throw new IOException("size of " + url + " is " + size + " bytes, expected " + source.sizeBytes());
        }
    }

    @Override
    public CompletionStage<ApplyResult> refresh(UUID player) {
        Objects.requireNonNull(player, "player");
        final Player online = plugin.getServer().getPlayer(player).orElse(null);
        if (online == null) {
            return CompletableFuture.completedFuture(ApplyResult.failure(tracker.snapshot(player),
                    ManagedPackStatus.CANCELLED, "the player is not connected", false));
        }

        synchronized (online) {
            final ServerConnection server = plugin.getPackHandler().getConfigurationPhaseServer(online)
                    .or(online::getCurrentServer).orElse(null);
            if (server == null) {
                return CompletableFuture.completedFuture(ApplyResult.failure(tracker.snapshot(player),
                        ManagedPackStatus.CANCELLED, "the player is not on a backend", false));
            }

            final ManagedProfile profile = profiles.get(server.getServerInfo().getName());
            if (profile == null) {
                return CompletableFuture.completedFuture(ApplyResult.failure(tracker.snapshot(player),
                        ManagedPackStatus.UNKNOWN, "'" + server.getServerInfo().getName()
                                + "' is not a managed profile", false));
            }
            return apply(online, server, profile);
        }
    }

    @Override
    public PlayerPackState snapshot(UUID player) {
        return tracker.snapshot(player);
    }

    @Override
    public Registration subscribe(PackStateListener listener) {
        return tracker.subscribe(listener);
    }

    // ------------------------------------------------------------------ hosting

    private void collectHostedContent() {
        final ForcePackWebServer webServer = plugin.getWebServer().orElse(null);
        if (webServer == null) return;
        collectHostedContent(webServer.getManagedPacks());
    }

    void collectHostedContent(ManagedPackRegistry registry) {
        final Set<String> retain = new HashSet<>();
        for (ProviderEntry entry : providers) {
            try {
                for (PreparedPack pack : entry.provider.retainedPacks()) retain.add(pack.sha1());
            } catch (RuntimeException failed) {
                plugin.getLogger().error("Cannot read retained packs from '{}'; skipping collection.", entry.owner, failed);
                return;
            }
        }
        for (UUID player : tracker.players()) {
            retain.addAll(tracker.retainedContent(player));
        }
        for (SuccessfulSelection successful : lastSuccessful.values()) {
            for (PreparedPack pack : successful.selection.packs()) {
                retain.add(pack.sha1());
            }
        }

        final int removed = registry.collect(retain, retentionMillis());
        if (removed > 0) {
            plugin.log("Collected %d managed resource pack(s) nothing was using.", removed);
        }
    }

    // ------------------------------------------------------------------ config

    private @Nullable VelocityConfig managedConfig() {
        return plugin.getConfig().getConfig("managed");
    }

    private long clientTimeoutMillis() {
        final VelocityConfig managed = managedConfig();
        final long seconds = managed == null
                ? DEFAULT_CLIENT_TIMEOUT_SECONDS
                : managed.getLong("client-timeout-seconds", DEFAULT_CLIENT_TIMEOUT_SECONDS);
        return TimeUnit.SECONDS.toMillis(Math.max(1L, seconds));
    }

    private long prepareTimeoutSeconds() {
        final VelocityConfig managed = managedConfig();
        final long seconds = managed == null
                ? DEFAULT_PREPARE_TIMEOUT_SECONDS
                : managed.getLong("prepare-timeout-seconds", DEFAULT_PREPARE_TIMEOUT_SECONDS);
        return Math.max(1L, seconds);
    }

    private long retentionMillis() {
        final VelocityConfig managed = managedConfig();
        final long minutes = managed == null
                ? DEFAULT_RETENTION_MINUTES
                : managed.getLong("hosted-retention-minutes", DEFAULT_RETENTION_MINUTES);
        return TimeUnit.MINUTES.toMillis(Math.max(1L, minutes));
    }
}
