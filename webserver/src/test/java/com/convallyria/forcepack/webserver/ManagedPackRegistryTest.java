package com.convallyria.forcepack.webserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedPackRegistryTest {

    /** SHA-1 of "pack one". */
    private static final String ONE_SHA1 = "b4b1c9f0dac80b0f3e1d5c67e50c1c4b3a51d29a";

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ManagedPackRegistry registry(Path directory) {
        return new ManagedPackRegistry(directory.resolve("store"), now::get);
    }

    private static Path write(Path directory, String name, String content) throws IOException {
        final Path file = directory.resolve(name);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String sha1Of(Path file) throws Exception {
        final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
        final byte[] sum = digest.digest(Files.readAllBytes(file));
        final StringBuilder hex = new StringBuilder();
        for (byte b : sum) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    @Test
    void registeredContentIsServedUnderItsOwnHash(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path source = write(directory, "pack.zip", "pack one");
        final String sha1 = sha1Of(source);

        final ManagedPackRegistry.Entry entry = registry.register(source, sha1.toUpperCase(java.util.Locale.ROOT), 8L);

        assertEquals(sha1, entry.sha1());
        assertEquals("/managed/" + sha1 + ".zip", entry.servePath());
        assertTrue(Files.isRegularFile(entry.file()));
        assertEquals(sha1 + ".zip", entry.file().getFileName().toString());
        // Both the URL form and the bare identity resolve.
        assertTrue(registry.lookup(sha1 + ".zip").isPresent());
        assertTrue(registry.lookup(sha1).isPresent());
        assertFalse(registry.lookup("deadbeef").isPresent());
    }

    @Test
    void statedHashAndSizeAreVerifiedBeforePublishing(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path source = write(directory, "pack.zip", "pack one");
        final String sha1 = sha1Of(source);

        assertThrows(IOException.class, () -> registry.register(source, ONE_SHA1, 8L), "wrong hash");
        assertThrows(IOException.class, () -> registry.register(source, sha1, 9L), "wrong size");
        assertThrows(IllegalArgumentException.class, () -> registry.register(source, "not-a-hash", 8L));
        assertTrue(registry.hosted().isEmpty(), "a rejected source must not be hosted");
    }

    @Test
    void servedBytesDoNotFollowLaterEditsToTheSource(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path source = write(directory, "pack.zip", "pack one");
        final byte[] original = Files.readAllBytes(source);

        final ManagedPackRegistry.Entry entry = registry.register(source, sha1Of(source), original.length);
        // The old workflow overwrote the shared file in place. That must not change what a
        // client downloading this identity receives.
        Files.write(source, "something else entirely".getBytes(StandardCharsets.UTF_8));

        assertArrayEquals(original, Files.readAllBytes(entry.file()));
    }

    @Test
    void registeringTheSameContentTwiceReusesTheEntry(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path source = write(directory, "pack.zip", "pack one");
        final String sha1 = sha1Of(source);

        final ManagedPackRegistry.Entry first = registry.register(source, sha1, 8L);
        final ManagedPackRegistry.Entry second = registry.register(write(directory, "copy.zip", "pack one"), sha1, 8L);

        assertSame(first, second);
        assertEquals(1, registry.hosted().size());
    }

    @Test
    void collectionKeepsRetainedAndRecentEntries(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path pinnedSource = write(directory, "pinned.zip", "pack one");
        final Path oldSource = write(directory, "old.zip", "pack two");
        final Path freshSource = write(directory, "fresh.zip", "pack three");

        final ManagedPackRegistry.Entry pinned = registry.register(pinnedSource, sha1Of(pinnedSource), Files.size(pinnedSource));
        final ManagedPackRegistry.Entry old = registry.register(oldSource, sha1Of(oldSource), Files.size(oldSource));

        now.addAndGet(60_000L);
        final ManagedPackRegistry.Entry fresh = registry.register(freshSource, sha1Of(freshSource), Files.size(freshSource));

        // Retention of 30s: the pinned entry is named, the fresh one is inside the grace period.
        final int removed = registry.collect(new HashSet<>(Collections.singletonList(pinned.sha1())), 30_000L);

        assertEquals(1, removed);
        assertTrue(registry.lookup(pinned.sha1()).isPresent(), "a retained pin must survive");
        assertTrue(registry.lookup(fresh.sha1()).isPresent(), "an entry inside the grace period must survive");
        assertFalse(registry.lookup(old.sha1()).isPresent(), "an aged-out development entry is collected");
        assertFalse(Files.exists(old.file()), "collection deletes the store file");
    }

    @Test
    void servingAnEntryRenewsItsGracePeriod(@TempDir Path directory) throws Exception {
        final ManagedPackRegistry registry = registry(directory);
        final Path source = write(directory, "pack.zip", "pack one");
        final ManagedPackRegistry.Entry entry = registry.register(source, sha1Of(source), Files.size(source));

        now.addAndGet(60_000L);
        final Optional<ManagedPackRegistry.Entry> served = registry.lookup(entry.sha1());
        assertTrue(served.isPresent());

        assertEquals(0, registry.collect(Collections.emptySet(), 30_000L), "a download in progress is recent use");
    }

    @Test
    void repairsSameSizeCorruptionAfterRestart(@TempDir Path directory) throws Exception {
        final Path source = write(directory, "pack.zip", "pack one");
        final String hash = sha1Of(source);
        final ManagedPackRegistry.Entry entry = registry(directory).register(source, hash, 8L);
        Files.writeString(entry.file(), "bad data");

        final ManagedPackRegistry restarted = registry(directory);
        restarted.register(source, hash, 8L);
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(restarted.lookup(hash).orElseThrow().file()));
    }

    @Test
    void rejectsSourceMutationDuringCopy(@TempDir Path directory) throws Exception {
        final Path source = write(directory, "pack.zip", "pack one");
        final String hash = sha1Of(source);
        final ManagedPackRegistry registry = new ManagedPackRegistry(directory.resolve("store"), now::get,
                (from, to) -> {
                    Files.writeString(from, "bad data");
                    Files.copy(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                });

        assertThrows(IOException.class, () -> registry.register(source, hash, 8L));
        assertTrue(registry.lookup(hash).isEmpty());
        try (java.util.stream.Stream<Path> files = Files.list(directory.resolve("store"))) {
            assertEquals(0L, files.count(), "no partial artifact remains published or on disk");
        }
    }

    @Test
    void concurrentRegistrationsAndReadsSeeCompleteBytes(@TempDir Path directory) throws Exception {
        final Path source = write(directory, "pack.zip", "pack one");
        final String hash = sha1Of(source);
        final byte[] expected = Files.readAllBytes(source);
        final ManagedPackRegistry registry = registry(directory);
        final java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            final java.util.List<java.util.concurrent.Callable<Void>> requests = new java.util.ArrayList<>();
            for (int i = 0; i < 40; i++) requests.add(() -> {
                registry.register(source, hash, expected.length);
                assertArrayEquals(expected, Files.readAllBytes(registry.lookup(hash).orElseThrow().file()));
                return null;
            });
            for (java.util.concurrent.Future<Void> request : pool.invokeAll(requests)) request.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
