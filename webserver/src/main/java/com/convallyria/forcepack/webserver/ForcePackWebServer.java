package com.convallyria.forcepack.webserver;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.util.JavalinLogger;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;

public class ForcePackWebServer {

    private final Javalin app;
    private final Path dataFolder;
    private final boolean usePort;
    private final String protocol;
    private final String ipAddress;
    private final int port;
    // Static configuration packs. Cleared and rebuilt on every reload, and read from request
    // threads while that happens.
    private final Map<File, String> hostedPacks = new ConcurrentHashMap<>();
    // Managed packs. Content-indexed and immutable, so a reload cannot invalidate a URL that
    // a prepared selection or an in-flight download is still using.
    private final ManagedPackRegistry managedPacks;

    public ForcePackWebServer(Path dataFolder, String protocol, String serverIp, int port, boolean usePort) throws IOException {
        JavalinLogger.enabled = false;
        JavalinLogger.startupInfo = false;
        this.app = Javalin.create(config -> config.showJavalinBanner = false).start(port);
        this.dataFolder = dataFolder;
        this.managedPacks = new ManagedPackRegistry(dataFolder.resolve("managed"));
        setupEndpoints();
        this.usePort = usePort;
        this.protocol = protocol;
        this.ipAddress = serverIp;
        this.port = port;
    }

    public void addHostedPack(File hostedPack) {
        try {
            this.hostedPacks.put(hostedPack, Files.asByteSource(hostedPack).hash(Hashing.sha1()).toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void clearHostedPacks() {
        this.hostedPacks.clear();
    }

    /**
     * The content-indexed registry used for managed packs.
     *
     * <p>Deliberately not cleared by {@link #clearHostedPacks()}: a managed URL stays valid
     * across reloads until its content is explicitly collected.</p>
     *
     * @return the managed registry
     */
    public ManagedPackRegistry getManagedPacks() {
        return managedPacks;
    }

    /**
     * Hosts an immutable local file and returns the URL a client can download it from.
     *
     * @param file the file to host
     * @param sha1 the expected SHA-1 of that file
     * @param sizeBytes the expected size in bytes
     * @return the client-downloadable URL
     * @throws IOException if the bytes cannot be read or do not match
     */
    public String hostManagedPack(Path file, String sha1, long sizeBytes) throws IOException {
        return getUrl() + managedPacks.register(file, sha1, sizeBytes).servePath();
    }

    public void shutdown() {
        app.stop();
    }

    public String getUrl() {
        return protocol + ipAddress + (usePort ? ":" + port : "");
    }

    public String getHostedEndpoint(String urlString) {
        final File targetFile = new File(dataFolder + File.separator + urlString.replace("forcepack://", ""));
        return getUrl() + "/serve/" + hostedPacks.get(targetFile) + ".zip";
    }

    private void setupEndpoints() {
        app.get("/", ctx -> ctx.status(HttpStatus.NO_CONTENT));

        app.get("/serve/<id>", ctx -> {
            final String id = ctx.pathParam("id");
            // Headers
            // {X-Minecraft-Version=1.20.1, X-Minecraft-Version-ID=1.20.1, X-Minecraft-Username=Cotander,
            // Accept=text/html, image/gif, image/jpeg, *; q=.2, */*; q=.2,
            // X-Minecraft-Pack-Format=15, User-Agent=Minecraft Java/1.20.1, Connection=keep-alive,
            // X-Minecraft-UUID=4b319cd4e8274dcfa3039a3fce310755, Host=localhost:2222}

            // If this is the real resource pack
            for (Map.Entry<File, String> hostedPack : hostedPacks.entrySet()) {
                if (id.equals(hostedPack.getValue() + ".zip")) {
                    serve(ctx, hostedPack.getKey().toPath(), hostedPack.getKey().getName());
                    return;
                }
            }

            // Nothing is hosted under that identity. Saying so beats handing the client an
            // empty body it will report as a corrupt pack.
            ctx.status(HttpStatus.NOT_FOUND).result("");
        });

        app.get(ManagedPackRegistry.PATH_PREFIX + "<id>", ctx -> {
            final String id = ctx.pathParam("id");
            final ManagedPackRegistry.Entry entry = managedPacks.lookup(id).orElse(null);
            if (entry == null) {
                ctx.status(HttpStatus.NOT_FOUND).result("");
                return;
            }
            serve(ctx, entry.file(), entry.sha1() + ".zip");
        });
    }

    private static void serve(io.javalin.http.Context ctx, Path file, String fileName) throws IOException {
        // Streamed rather than read into a byte[]: a pack is megabytes and every concurrent
        // download would otherwise hold its own copy in heap.
        final InputStream stream = java.nio.file.Files.newInputStream(file);
        ctx.result(stream)
                .header("X-Hosted-By", "forcepack")
                .header("Content-Type", "application/zip")
                .header("Content-Length", String.valueOf(java.nio.file.Files.size(file)))
                .header("Content-Disposition", "attachment; filename=" + fileName);
    }

    public static String getIp() {
        // Copied from https://github.com/oraxen/oraxen/pull/986
        try {
            URL url = new URL("https://api.ipify.org");
            InputStream stream = url.openStream();
            Scanner s = new Scanner(stream, StandardCharsets.UTF_8).useDelimiter("\\A");
            String ip = s.next();
            s.close();
            stream.close();
            return ip;
        } catch (IOException e) {
            e.printStackTrace();
            return "localhost";
        }
    }
}
